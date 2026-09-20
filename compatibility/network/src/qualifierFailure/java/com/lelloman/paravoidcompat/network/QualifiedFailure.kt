package com.lelloman.paravoidcompat.network

import com.squareup.moshi.JsonClass

// Opt-in reproducer: Moshi 1.15.2's qualifier processing fails under the pinned KSP2.
@JsonClass(generateAdapter = true)
data class QualifiedFailure(@Uppercase val label: String)
