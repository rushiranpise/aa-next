package uk.noammm.aamods

// The flag editor works on the same lines Entry.flagOverrides() reads: Flag__name=value, one per line,
// with # comments skipped. Editing line by line (instead of rebuilding the whole list) keeps comments,
// hand-written names and their order exactly as they were, so the text box stays a real escape hatch.
object FlagOverrides {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9]*__[A-Za-z0-9_]+$")

    // Entry only requires a non-empty name and an '='; the same here, so an override written by hand in
    // a shape the catalog doesn't know still shows up and can be edited or removed
    private fun split(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) return null
        val name = trimmed.substringBefore('=').trim()
        if (name.isEmpty()) return null
        return name to trimmed.substringAfter('=').trim()
    }

    fun parse(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line -> split(line)?.let { (name, value) -> map[name] = value } }
        return map
    }

    fun count(raw: String) = parse(raw).size

    fun validName(name: String) = NAME.matches(name)

    fun accepts(type: FlagType, value: String) = when (type) {
        FlagType.BOOLEAN -> value == "true" || value == "false"
        FlagType.LONG -> value.toLongOrNull() != null
        FlagType.DOUBLE -> value.toDoubleOrNull() != null
        FlagType.STRING -> true
    }

    // A hand-added flag has no catalog type; the stored text is the only hint. Entry converts by the
    // getter's real type, so this only picks the editor.
    fun typeOf(value: String) = when {
        value == "true" || value == "false" -> FlagType.BOOLEAN
        value.toLongOrNull() != null -> FlagType.LONG
        value.toDoubleOrNull() != null -> FlagType.DOUBLE
        else -> FlagType.STRING
    }

    // Replaces every line with this name, then puts the new one where the first was. The runtime map is
    // last-wins too, so leaving duplicates behind would make the editor and the hook disagree.
    fun set(raw: String, name: String, value: String): String {
        val lines = raw.lines().toMutableList()
        val first = lines.indexOfFirst { split(it)?.first == name }
        lines.removeAll { split(it)?.first == name }
        if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        lines.add(if (first >= 0) first.coerceAtMost(lines.size) else lines.size, "$name=$value")
        return lines.joinToString("\n")
    }

    fun remove(raw: String, name: String): String =
        raw.lines().filterNot { split(it)?.first == name }.joinToString("\n")
}
