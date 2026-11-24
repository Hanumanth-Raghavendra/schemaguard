package io.github.hanumanthraghavendra.schemaguard.api.projects;

import org.jspecify.annotations.Nullable;

/** A project in the list of an account's projects: how many versions, and the latest one. */
public record ProjectListing(Project project, int versions, @Nullable SavedVersion latest) {}
