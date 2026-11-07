package com.kaze.newage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.i18n.t

/**
 * 「新建技能」对话框。
 *
 * ## 为什么单独成文件
 * [AiSettingsScreen] 已经 560 行，且技能这块的插入已经出过两次结构事故
 * （孤立括号、函数体被吞）。新建表单独立成一个文件，改动面最小。
 *
 * ## 交互约定
 * - 名字与正文都必填：名字决定目录名，正文是技能的本体；
 * - 描述可选，它只影响 AI 清单里那一行的可读性；
 * - **提交按钮在正文为空时禁用** —— 与其点下去再报错，不如一开始就按不动；
 * - 失败原因回填到对话框里（不关闭），用户可以改完直接重试。
 */
@Composable
internal fun SkillCreateDialog(
    onDismiss: () -> Unit,
    onSubmit: (name: String, description: String, body: String) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val canSubmit = name.isNotBlank() && body.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("新建技能")) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    t("技能是给 AI 看的本地说明文档：它会出现在 AI 的清单里，需要时自己读取。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(t("技能名（同时作为目录名）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(t("一句话描述（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        error = null
                    },
                    label = { Text(t("正文（这个技能要让 AI 记住什么）")) },
                    minLines = 6,
                    maxLines = 14,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val err = onSubmit(name.trim(), description.trim(), body)
                    if (err == null) onDismiss() else error = err
                },
            ) { Text(t("创建")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}

/**
 * 「删除技能」确认框。
 *
 * 独立确认而不是列表里直接删：技能是用户手写的文档，误触一次就没了，
 * 而且删除会连同整个 `skills/<名字>/` 目录一起移除。
 */
@Composable
internal fun SkillDeleteDialog(
    skillName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> String?,
) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("删除技能「%s」？", skillName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    t("整个 skills/%s/ 目录会被删除，无法恢复。", skillName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = onConfirm()
                if (err == null) onDismiss() else error = err
            }) {
                Text(t("删除"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}

/**
 * 「从文件导入技能」的错误提示框。
 *
 * 导入走系统文件选择器，失败（同名已存在、文件为空、读不出）需要一个明确落点，
 * 否则用户点完选择器什么都没发生。
 */
@Composable
internal fun SkillImportErrorDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("导入失败")) },
        text = { Text(reason, style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("知道了")) } },
    )
}