/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import me.rerere.rikkahub.data.codehut.redactCodeHutUiText

/** Harness lifecycle failures may echo shell commands or provider environment details. */
fun redactHarnessUiNotice(value: String): String = redactCodeHutUiText(value)
