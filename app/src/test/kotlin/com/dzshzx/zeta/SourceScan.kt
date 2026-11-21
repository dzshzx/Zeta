package com.dzshzx.zeta

import java.io.File

/**
 * 源码检查用：[dir]（相对 app 模块，含子目录）下有一行匹配 [pattern] 的 Kotlin 文件的文件名，[except] 里的文件不查。
 * 拦「应用代码或测试里不直接用某个写法」的测试都用它，断言结果为空。
 */
internal fun sourcesMatching(
    dir: String,
    pattern: Regex,
    except: Set<String> = emptySet(),
): List<String> =
    File(dir)
        .walk()
        .filter { f -> f.extension == "kt" && f.name !in except }
        .filter { f -> f.useLines { lines -> lines.any(pattern::containsMatchIn) } }
        .map { it.name }
        .toList()
