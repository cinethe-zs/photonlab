#version 330 core

// PhotonLab tone-adjustment shader — GLSL port of edit_shader.agsl
// Executed via LWJGL OpenGL off-screen FBO for GPU-accelerated tone processing.
//
// All uniform values are pre-normalised by the Kotlin caller to match applyToneSoftware():
//   exposure    : EV offset          range -5..+5
//   luminosity  : linear offset      range -0.5..+0.5   (slider / 200)
//   contrast    : scale offset       range -1..+1       (slider / 100)
//   saturation  : saturation delta   range -1..+0.5     (normalised asymmetrically)
//   vibrance    : vibrance           range -1..+1       (slider / 100)
//   highlights  : highlight lift     range -0.5..+0.5   (slider / 200)
//   shadows     : shadow lift        range -0.5..+0.5   (slider / 200)
//   temperature : warm/cool          range -1..+1       (slider / 100)
//   tint        : green/magenta      range -1..+1       (slider / 100)

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D inputImage;
uniform float exposure;
uniform float luminosity;
uniform float contrast;
uniform float saturation;
uniform float vibrance;
uniform float highlights;
uniform float shadows;
uniform float temperature;
uniform float tint;

float srgbToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float linearToSrgb(float c) {
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

float luminance(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

vec3 rgbToHsl(vec3 rgb) {
    float maxC = max(rgb.r, max(rgb.g, rgb.b));
    float minC = min(rgb.r, min(rgb.g, rgb.b));
    float delta = maxC - minC;
    float l = (maxC + minC) * 0.5;
    if (delta < 0.00001) return vec3(0.0, 0.0, l);
    float s = delta / (1.0 - abs(2.0 * l - 1.0));
    float h;
    if (maxC == rgb.r)      h = mod((rgb.g - rgb.b) / delta, 6.0);
    else if (maxC == rgb.g) h = (rgb.b - rgb.r) / delta + 2.0;
    else                    h = (rgb.r - rgb.g) / delta + 4.0;
    h /= 6.0;
    return vec3(h, s, l);
}

vec3 hslToRgb(vec3 hsl) {
    float h = hsl.x, s = hsl.y, l = hsl.z;
    float c = (1.0 - abs(2.0 * l - 1.0)) * s;
    float h6 = h * 6.0;
    float x = c * (1.0 - abs(mod(h6, 2.0) - 1.0));
    float m = l - c * 0.5;
    vec3 rgb;
    if      (h6 < 1.0) rgb = vec3(c, x, 0.0);
    else if (h6 < 2.0) rgb = vec3(x, c, 0.0);
    else if (h6 < 3.0) rgb = vec3(0.0, c, x);
    else if (h6 < 4.0) rgb = vec3(0.0, x, c);
    else if (h6 < 5.0) rgb = vec3(x, 0.0, c);
    else                rgb = vec3(c, 0.0, x);
    return rgb + m;
}

void main() {
    vec4 color = texture(inputImage, texCoord);
    vec3 rgb = color.rgb;

    // --- Exposure (operate in linear light) ---
    if (exposure != 0.0) {
        vec3 lin = vec3(srgbToLinear(rgb.r), srgbToLinear(rgb.g), srgbToLinear(rgb.b));
        lin *= pow(2.0, exposure);
        lin = clamp(lin, 0.0, 1.0);
        rgb = vec3(linearToSrgb(lin.r), linearToSrgb(lin.g), linearToSrgb(lin.b));
    }

    // --- Luminosity ---
    if (luminosity != 0.0) {
        rgb = clamp(rgb + luminosity, 0.0, 1.0);
    }

    // --- Contrast: scale around mid-grey ---
    if (contrast != 0.0) {
        rgb = clamp((rgb - 0.5) * (1.0 + contrast) + 0.5, 0.0, 1.0);
    }

    // --- Highlights & Shadows (luma-masked) ---
    if (highlights != 0.0 || shadows != 0.0) {
        float lum = luminance(rgb);
        float hMask = max(0.0, (lum - 0.5) * 2.0);
        float sMask = max(0.0, (0.5 - lum) * 2.0);
        rgb = clamp(rgb + highlights * hMask + shadows * sMask, 0.0, 1.0);
    }

    // --- Temperature (warm/cool) and Tint (green/magenta) ---
    if (temperature != 0.0 || tint != 0.0) {
        rgb.r = clamp(rgb.r + temperature * 0.15, 0.0, 1.0);
        rgb.g = clamp(rgb.g - tint * 0.15, 0.0, 1.0);
        rgb.b = clamp(rgb.b - temperature * 0.2, 0.0, 1.0);
    }

    // --- Saturation + Vibrance (HSL) ---
    if (saturation != 0.0 || vibrance != 0.0) {
        vec3 hsl = rgbToHsl(rgb);
        hsl.y = clamp(hsl.y + saturation, 0.0, 1.0);
        if (vibrance >= 0.0) {
            hsl.y = clamp(hsl.y + vibrance * 0.35 * (1.0 - hsl.y) * (1.0 - hsl.y * 0.5), 0.0, 1.0);
        } else {
            hsl.y = clamp(hsl.y * (1.0 + vibrance * 0.35), 0.0, 1.0);
        }
        rgb = hslToRgb(hsl);
    }

    fragColor = vec4(rgb, color.a);
}
