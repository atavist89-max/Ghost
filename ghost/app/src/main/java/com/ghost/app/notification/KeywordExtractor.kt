package com.ghost.app.notification

/**
 * Deterministic keyword extractor (zero LLM).
 *
 * Algorithm:
 * 1. Lowercase the input
 * 2. Strip punctuation via regex [^a-z0-9\s]
 * 3. Split on whitespace
 * 4. Keep only words with length >= 2
 * 5. Remove stop words
 * 6. Fallback: if nothing remains, take the 3 longest words from step 4
 */
object KeywordExtractor {

    private val STOP_WORDS = setOf(
        // Articles
        "a", "an", "the",
        // Prepositions
        "about", "above", "across", "after", "against", "along", "amid", "among", "around", "at",
        "before", "behind", "below", "beneath", "beside", "besides", "between", "beyond", "but", "by",
        "concerning", "considering", "despite", "down", "during", "except", "for", "from", "in",
        "inside", "into", "like", "near", "of", "off", "on", "onto", "out", "outside", "over",
        "past", "regarding", "round", "since", "through", "throughout", "till", "to", "toward",
        "towards", "under", "underneath", "until", "up", "upon", "with", "within", "without",
        // Conjunctions
        "although", "and", "as", "because", "before", "but", "for", "if", "lest", "nor", "once",
        "or", "since", "so", "than", "that", "though", "unless", "until", "when", "whenever",
        "where", "whereas", "wherever", "whether", "while", "yet",
        // Pronouns
        "all", "another", "any", "anybody", "anyone", "anything", "both", "each", "either",
        "everybody", "everyone", "everything", "few", "he", "her", "hers", "herself", "him",
        "himself", "his", "i", "it", "its", "itself", "many", "me", "mine", "more", "most",
        "much", "my", "myself", "neither", "no", "nobody", "none", "noone", "nothing", "one",
        "other", "others", "our", "ours", "ourselves", "several", "she", "some", "somebody",
        "someone", "something", "such", "that", "their", "theirs", "them", "themselves", "these",
        "they", "this", "those", "us", "we", "what", "whatever", "which", "whichever", "who",
        "whoever", "whom", "whomever", "whose", "you", "your", "yours", "yourself", "yourselves",
        // Auxiliary / common verbs
        "am", "are", "aren", "be", "been", "being", "can", "could", "couldn", "did", "didn",
        "do", "does", "doesn", "doing", "don", "done", "had", "hadn", "has", "hasn", "have",
        "haven", "having", "is", "isn", "may", "might", "mightn", "must", "mustn", "need",
        "needn", "ought", "shall", "shan", "should", "shouldn", "was", "wasn", "were", "weren",
        "will", "won", "would", "wouldn",
        // Common adverbs & filler
        "also", "already", "always", "anyway", "anywhere", "back", "clearly", "completely",
        "constantly", "definitely", "earlier", "early", "easily", "either", "else", "enough",
        "even", "eventually", "ever", "everywhere", "exactly", "finally", "frequently",
        "generally", "hardly", "here", "how", "however", "indeed", "instead", "just", "late",
        "later", "maybe", "merely", "more", "most", "mostly", "much", "nearly", "never",
        "next", "no", "not", "now", "nowhere", "often", "only", "otherwise", "perhaps",
        "probably", "quite", "rather", "really", "recently", "rarely", "seldom", "simply",
        "since", "so", "somehow", "somewhat", "somewhere", "soon", "still", "such", "suddenly",
        "then", "there", "therefore", "thus", "today", "together", "too", "usually", "very",
        "well", "when", "where", "why", "yet",
        // Contractions / fragments often left by tokenizer
        "ain", "d", "ll", "m", "o", "re", "s", "t", "ve", "y", "ma"
    )

    fun extract(query: String): List<String> {
        val lower = query.lowercase()
        val stripped = lower.replace(Regex("[^a-z0-9\\s]"), "")
        val words = stripped.split(Regex("\\s+")).filter { it.length >= 2 }
        val filtered = words.filter { it !in STOP_WORDS }

        return if (filtered.isEmpty()) {
            words.sortedByDescending { it.length }.take(3)
        } else {
            filtered
        }
    }
}
