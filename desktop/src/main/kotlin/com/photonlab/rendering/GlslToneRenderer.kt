package com.photonlab.rendering

import com.photonlab.platform.DesktopBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_BGRA
import org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL33.glBindVertexArray
import org.lwjgl.opengl.GL33.glGenVertexArrays
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU-accelerated tone pass via LWJGL/OpenGL.
 *
 * A single hidden GLFW window provides the OpenGL 3.3 context. All GL calls run
 * on a dedicated single-threaded executor so that no context-current conflicts
 * occur with Compose's Skia renderer.
 *
 * Falls back silently to CPU rendering when GPU init fails (e.g. headless CI).
 */
class GlslToneRenderer {

    // Dedicated GL thread — all OpenGL operations must happen here
    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "photonlab-gl").apply { isDaemon = true }
    }
    private val glDispatcher = glExecutor.asCoroutineDispatcher()

    private val initialised = AtomicBoolean(false)
    private val initFailed  = AtomicBoolean(false)

    private var glfwWindow   = NULL
    private var shaderProg   = 0
    private var texId        = 0
    private var fboId        = 0
    private var fboTexId     = 0
    private var fboW         = 0
    private var fboH         = 0
    private var vaoId        = 0

    // Uniform locations
    private var uExposure    = -1
    private var uLuminosity  = -1
    private var uContrast    = -1
    private var uSaturation  = -1
    private var uVibrance    = -1
    private var uHighlights  = -1
    private var uShadows     = -1
    private var uTemperature = -1
    private var uTint        = -1
    private var uInputImage  = -1

    // ── Public API ──────────────────────────────────────────────────────────

    data class ToneUniforms(
        val exposure: Float,
        val luminosity: Float,
        val contrast: Float,
        val saturation: Float,
        val vibrance: Float,
        val highlights: Float,
        val shadows: Float,
        val temperature: Float,
        val tint: Float,
    )

    /**
     * Apply tone adjustments to [src] via OpenGL GLSL shader.
     * Returns null if GPU rendering fails (caller should fall back to CPU).
     */
    suspend fun render(src: DesktopBitmap, uniforms: ToneUniforms): DesktopBitmap? {
        if (initFailed.get()) return null
        return withContext(glDispatcher) {
            runCatching {
                if (!initialised.get()) initGl()
                if (initFailed.get()) return@withContext null
                renderFrame(src, uniforms)
            }.getOrNull()
        }
    }

    fun destroy() {
        glExecutor.submit {
            if (initialised.get()) {
                glfwMakeContextCurrent(glfwWindow)
                if (vaoId    != 0) glDeleteVertexArrays(vaoId)
                if (texId    != 0) glDeleteTextures(texId)
                if (fboTexId != 0) glDeleteTextures(fboTexId)
                if (fboId    != 0) glDeleteFramebuffers(fboId)
                if (shaderProg != 0) glDeleteProgram(shaderProg)
                glfwDestroyWindow(glfwWindow)
                glfwTerminate()
            }
        }.get()
        glExecutor.shutdown()
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    private fun initGl() {
        runCatching {
            if (!glfwInit()) error("GLFW init failed")

            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE,                GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,  3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,  3)
            glfwWindowHint(GLFW_OPENGL_PROFILE,         GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,  GLFW_TRUE)

            glfwWindow = glfwCreateWindow(1, 1, "photonlab-offscreen", NULL, NULL)
            if (glfwWindow == NULL) error("Failed to create GLFW window")

            glfwMakeContextCurrent(glfwWindow)
            GL.createCapabilities()

            // Compile and link shaders
            val vertSrc = GlslToneRenderer::class.java.getResource("/shaders/tone.vert")!!
                .readText()
            val fragSrc = GlslToneRenderer::class.java.getResource("/shaders/tone.frag")!!
                .readText()

            val vert = compileShader(GL_VERTEX_SHADER, vertSrc)
            val frag = compileShader(GL_FRAGMENT_SHADER, fragSrc)
            shaderProg = glCreateProgram()
            glAttachShader(shaderProg, vert)
            glAttachShader(shaderProg, frag)
            glLinkProgram(shaderProg)
            check(glGetProgrami(shaderProg, GL_LINK_STATUS) == GL_TRUE) {
                "Shader link error: ${glGetProgramInfoLog(shaderProg)}"
            }
            glDeleteShader(vert)
            glDeleteShader(frag)

            // Cache uniform locations
            glUseProgram(shaderProg)
            uExposure    = glGetUniformLocation(shaderProg, "exposure")
            uLuminosity  = glGetUniformLocation(shaderProg, "luminosity")
            uContrast    = glGetUniformLocation(shaderProg, "contrast")
            uSaturation  = glGetUniformLocation(shaderProg, "saturation")
            uVibrance    = glGetUniformLocation(shaderProg, "vibrance")
            uHighlights  = glGetUniformLocation(shaderProg, "highlights")
            uShadows     = glGetUniformLocation(shaderProg, "shadows")
            uTemperature = glGetUniformLocation(shaderProg, "temperature")
            uTint        = glGetUniformLocation(shaderProg, "tint")
            uInputImage  = glGetUniformLocation(shaderProg, "inputImage")

            // OpenGL 3.3 Core Profile requires a VAO bound for any draw call,
            // even when no vertex attributes are used (gl_VertexID only).
            vaoId = glGenVertexArrays()
            glBindVertexArray(vaoId)

            // Source image texture (slot 0)
            texId = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, texId)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

            // FBO + FBO colour texture (slot 1) — resized on demand
            fboId    = glGenFramebuffers()
            fboTexId = glGenTextures()

            initialised.set(true)
        }.onFailure { e ->
            System.err.println("PhotonLab: GPU init failed, using CPU fallback. (${e.message})")
            initFailed.set(true)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = glCreateShader(type)
        glShaderSource(id, src)
        glCompileShader(id)
        check(glGetShaderi(id, GL_COMPILE_STATUS) == GL_TRUE) {
            "Shader compile error:\n${glGetShaderInfoLog(id)}\n---\n$src"
        }
        return id
    }

    // ── Per-frame rendering ────────────────────────────────────────────────

    private fun renderFrame(src: DesktopBitmap, u: ToneUniforms): DesktopBitmap {
        val w = src.width
        val h = src.height

        // --- Upload source texture ---
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Pack into ByteBuffer as RGBA for GL upload (GL_BGRA + UNSIGNED_INT_8_8_8_8_REV
        // maps directly from Java's ARGB int without per-pixel swizzle)
        val srcBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        srcBuf.asIntBuffer().put(srcPixels)
        srcBuf.rewind()

        glBindTexture(GL_TEXTURE_2D, texId)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, srcBuf)

        // --- Resize FBO if needed ---
        if (fboW != w || fboH != h) {
            glBindTexture(GL_TEXTURE_2D, fboTexId)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glBindFramebuffer(GL_FRAMEBUFFER, fboId)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexId, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                "FBO incomplete"
            }
            fboW = w; fboH = h
        }

        // --- Bind FBO and render ---
        glBindFramebuffer(GL_FRAMEBUFFER, fboId)
        glViewport(0, 0, w, h)

        glUseProgram(shaderProg)
        glUniform1f(uExposure,    u.exposure)
        glUniform1f(uLuminosity,  u.luminosity)
        glUniform1f(uContrast,    u.contrast)
        glUniform1f(uSaturation,  u.saturation)
        glUniform1f(uVibrance,    u.vibrance)
        glUniform1f(uHighlights,  u.highlights)
        glUniform1f(uShadows,     u.shadows)
        glUniform1f(uTemperature, u.temperature)
        glUniform1f(uTint,        u.tint)
        glUniform1i(uInputImage,  0)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texId)

        // Fullscreen triangle (no VBO — positions generated in vertex shader via gl_VertexID)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // --- Read back pixels ---
        val outBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        glReadPixels(0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, outBuf)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        // --- Build output DesktopBitmap ---
        val outPixels = IntArray(w * h)
        outBuf.rewind()
        outBuf.asIntBuffer().get(outPixels)

        // OpenGL reads rows bottom-to-top; flip vertically
        val flipped = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w
            val dstRow = y * w
            System.arraycopy(outPixels, srcRow, flipped, dstRow, w)
        }

        val out = DesktopBitmap.create(w, h)
        out.setPixels(flipped, 0, w, 0, 0, w, h)
        return out
    }
}
