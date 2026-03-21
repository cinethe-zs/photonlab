#version 330 core

// Fullscreen triangle — covers the viewport in a single draw call.
// Vertex positions are generated from gl_VertexID (no VBO needed).
// Texture coordinates run [0,1] covering the entire image.

out vec2 texCoord;

void main() {
    // Triangle that covers [-1,1] clip space:
    //  id=0 → (-1,-1),  id=1 → (3,-1),  id=2 → (-1,3)
    vec2 pos = vec2(
        (gl_VertexID == 1) ? 3.0 : -1.0,
        (gl_VertexID == 2) ? 3.0 : -1.0
    );
    // Flip Y: OpenGL textures have origin at bottom-left but images are top-to-bottom
    texCoord = vec2(pos.x * 0.5 + 0.5, 1.0 - (pos.y * 0.5 + 0.5));
    gl_Position = vec4(pos, 0.0, 1.0);
}
