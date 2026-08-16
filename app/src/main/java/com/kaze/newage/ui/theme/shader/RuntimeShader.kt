// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.kaze.newage.ui.theme.shader

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import org.intellij.lang.annotations.Language

interface RuntimeShader {

    /**
     * Sets a single-component `float` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value The scalar value to assign to the uniform.
     */
    fun setFloatUniform(name: String, value: Float)

    /**
     * Sets a two-component (`vec2`) `float` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     */
    fun setFloatUniform(name: String, value1: Float, value2: Float)

    /**
     * Sets a three-component (`vec3`) `float` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     * @param value3 The third component of the uniform.
     */
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)

    /**
     * Sets a four-component (`vec4`) `float` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     * @param value3 The third component of the uniform.
     * @param value4 The fourth component of the uniform.
     */
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)

    /**
     * Sets a `float` uniform from an array of components.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param values The array of float components to assign to the uniform.
     */
    fun setFloatUniform(name: String, values: FloatArray)

    /**
     * Sets a single-component `int` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value The scalar value to assign to the uniform.
     */
    fun setIntUniform(name: String, value: Int)

    /**
     * Sets a two-component (`int2`) `int` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     */
    fun setIntUniform(name: String, value1: Int, value2: Int)

    /**
     * Sets a three-component (`int3`) `int` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     * @param value3 The third component of the uniform.
     */
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)

    /**
     * Sets a four-component (`int4`) `int` uniform.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param value1 The first component of the uniform.
     * @param value2 The second component of the uniform.
     * @param value3 The third component of the uniform.
     * @param value4 The fourth component of the uniform.
     */
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)

    /**
     * Sets an `int` uniform from an array of components.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param values The array of int components to assign to the uniform.
     */
    fun setIntUniform(name: String, values: IntArray)

    /**
     * Sets a `vec4` uniform from a [Color], converted to the shader's expected component layout.
     *
     * @param name The name of the uniform as declared in the shader source.
     * @param color The [Color] to assign to the uniform.
     */
    fun setColorUniform(name: String, color: Color)

    /**
     * Binds a [Shader] as a child input shader of this runtime shader.
     *
     * @param name The name of the child shader as declared in the shader source.
     * @param shader The [Shader] to bind as the named input.
     */
    fun setInputShader(name: String, shader: Shader)
}

/** True on Android API 33+ and on every Skia backend. */
@SuppressLint("ObsoleteSdkInt")
@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Creates a platform-specific [RuntimeShader] from an AGSL/SkSL shader string.
 *
 * @param shaderString The AGSL shader source to compile.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader = AndroidRuntimeShader(android.graphics.RuntimeShader(shaderString))

/** 非歧义工厂：类型与构造函数同名时用于显式构造 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun createRuntimeShaderCompat(@Language("AGSL") shaderString: String): RuntimeShader = RuntimeShader(shaderString)

fun RuntimeShader.asComposeShader(): Shader = asAndroidRuntimeShader()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader.asBrush(): ShaderBrush = (this as AndroidRuntimeShader).brush

/** Returns the underlying [android.graphics.RuntimeShader] for interop with native render-effect APIs. */
fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader = (this as AndroidRuntimeShader).shader

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidRuntimeShader(val shader: android.graphics.RuntimeShader) : RuntimeShader {

    val brush: ShaderBrush = ShaderBrush(shader)

    override fun setFloatUniform(name: String, value: Float) {
        shader.setFloatUniform(name, value)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float) {
        shader.setFloatUniform(name, value1, value2)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) {
        shader.setFloatUniform(name, value1, value2, value3)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) {
        shader.setFloatUniform(name, value1, value2, value3, value4)
    }

    override fun setFloatUniform(name: String, values: FloatArray) {
        shader.setFloatUniform(name, values)
    }

    override fun setIntUniform(name: String, value: Int) {
        shader.setIntUniform(name, value)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int) {
        shader.setIntUniform(name, value1, value2)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) {
        shader.setIntUniform(name, value1, value2, value3)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) {
        shader.setIntUniform(name, value1, value2, value3, value4)
    }

    override fun setIntUniform(name: String, values: IntArray) {
        shader.setIntUniform(name, values)
    }

    override fun setColorUniform(name: String, color: Color) {
        shader.setColorUniform(name, color.toArgb())
    }

    override fun setInputShader(name: String, shader: Shader) {
        this.shader.setInputShader(name, shader)
    }
}
