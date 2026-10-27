package com.fcourt.discovery.persistence;

/** Published in the projection transaction; cache listeners act only after commit. */
public record BranchProjectionChanged(long branchId) {}
