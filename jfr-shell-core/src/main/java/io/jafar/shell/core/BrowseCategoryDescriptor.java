package io.jafar.shell.core;

/**
 * Describes the rendering and behavior of a single browse category so the TUI framework can display
 * sidebar entries without knowing engine-specific field names.
 *
 * @param sidebarTitle title for the sidebar border (e.g. "Classes", "Event Types")
 * @param countField field key whose value is shown as {@code (N)} after the name, or null
 * @param decorationField field key whose non-empty value triggers a suffix, or null
 * @param decorationSuffix the suffix string (e.g. {@code " *"}), or null
 * @param asyncLoading whether {@code loadBrowseSummary} is expensive and should run in background
 * @param hasMetadataClasses whether {@code loadMetadataClasses} applies (enables tree detail view)
 */
public record BrowseCategoryDescriptor(
    String sidebarTitle,
    String countField,
    String decorationField,
    String decorationSuffix,
    boolean asyncLoading,
    boolean hasMetadataClasses) {}
