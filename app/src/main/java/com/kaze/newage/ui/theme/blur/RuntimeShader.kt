// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("FunctionName")

package com.kaze.newage.ui.theme.blur

import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import com.kaze.newage.ui.theme.shader.asBrush as coreAsBrush
import com.kaze.newage.ui.theme.shader.asComposeShader as coreAsComposeShader
import com.kaze.newage.ui.theme.shader.isRuntimeShaderSupported as coreIsRuntimeShaderSupported

/** Back-compat re-export. New code should use `com.kaze.newage.ui.theme.shader.RuntimeShader`. */
typealias RuntimeShader = com.kaze.newage.ui.theme.shader.RuntimeShader

/**
 * Back-compat re-export.
 *
 * @param shaderString The AGSL/SkSL shader source code to compile into the [RuntimeShader].
 */
