package dev.whispr.domain.usecase

sealed interface DisplayNameValidation {
    data class Valid(val name: String) : DisplayNameValidation
    data object Empty : DisplayNameValidation
    data object TooLong : DisplayNameValidation
    data object InvalidCharacters : DisplayNameValidation
}

/**
 * Mirrors server-side validation (server/internal/auth/service.go,
 * ValidateDisplayName) so users get immediate feedback: trimmed, 1–64 code
 * points, no control characters, no bidi controls (which enable spoofing).
 */
object DisplayNameValidator {
    const val MAX_LENGTH = 64

    fun validate(input: String): DisplayNameValidation {
        val name = input.trim()
        if (name.isEmpty()) return DisplayNameValidation.Empty
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return DisplayNameValidation.TooLong
        val bad = name.codePoints().anyMatch { Character.isISOControl(it) || isBidiControl(it) || isLoneSurrogate(it) }
        if (bad) return DisplayNameValidation.InvalidCharacters
        return DisplayNameValidation.Valid(name)
    }

    private fun isBidiControl(cp: Int) =
        cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C

    // Unpaired surrogates cannot be encoded as valid UTF-8, which the server requires.
    private fun isLoneSurrogate(cp: Int) = cp in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
}
