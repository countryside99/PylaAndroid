package com.pyla.ai.ui

import com.pyla.ai.pyla.PlaystyleImportFailure
import com.pyla.ai.pyla.PlaystyleImportResult

/** Fixed user-facing vocabulary: never display script text, document URIs or exception messages. */
internal fun playstyleImportFailureText(failure: PlaystyleImportResult.Failure): String =
    when (failure.reason) {
        PlaystyleImportFailure.EMPTY -> "Import failed: the file is empty."
        PlaystyleImportFailure.TOO_LARGE -> "Import failed: the file exceeds the 512 KiB limit."
        PlaystyleImportFailure.INVALID_ENCODING -> "Import failed: save the .pyla file as UTF-8 text."
        PlaystyleImportFailure.INVALID_SCRIPT -> {
            val line = failure.line?.takeIf { it in 1..1_000_000 }
            "Import failed: unsupported or invalid .pyla syntax" +
                (line?.let { " near line $it" } ?: "") +
                ". PC scripts may require features this Android version does not support."
        }
        PlaystyleImportFailure.READ_FAILED -> "Import failed: the selected document could not be read. Download it locally and try again."
        PlaystyleImportFailure.WRITE_FAILED -> "Import failed: the playstyle could not be saved. Existing files were not replaced."
    }
