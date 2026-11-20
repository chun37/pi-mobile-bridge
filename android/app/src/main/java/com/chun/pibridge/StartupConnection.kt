package com.chun.pibridge

/** Prefer the last chosen PC; an absent or removed profile falls back to the first saved PC. */
fun startupProfile(profiles: List<Profile>, lastProfileId: String): Profile? =
    profiles.find { it.id == lastProfileId } ?: profiles.firstOrNull()
