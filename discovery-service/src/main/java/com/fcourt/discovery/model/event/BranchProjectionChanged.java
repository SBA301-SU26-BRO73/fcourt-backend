package com.fcourt.discovery.model.event;

/** Published in the projection transaction; cache listeners act only after commit. */
public record BranchProjectionChanged(long branchId) {}
