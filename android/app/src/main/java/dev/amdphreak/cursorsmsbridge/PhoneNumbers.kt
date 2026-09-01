package dev.amdphreak.cursorsmsbridge

object PhoneNumbers {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val digitsOnly = trimmed.filter { it.isDigit() || it == '+' }
        return if (digitsOnly.startsWith("+")) {
            "+" + digitsOnly.drop(1).filter { it.isDigit() }
        } else {
            digitsOnly.filter { it.isDigit() }
        }
    }

    fun isAllowed(from: String, allowed: Set<String>, forwardAll: Boolean): Boolean {
        if (forwardAll || allowed.isEmpty()) {
            return true
        }
        val normalizedFrom = normalize(from)
        return allowed.any { normalize(it) == normalizedFrom }
    }
}
