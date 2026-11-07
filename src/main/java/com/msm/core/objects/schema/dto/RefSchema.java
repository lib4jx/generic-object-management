package com.msm.core.objects.schema.dto;

import java.util.List;

/**
 * The object an attribute refers to.
 *
 * @param object         the referenced object, as its service names it (for example {@code geography})
 * @param snapshotField  the JSON field holding the snapshot of the referenced record ({@code <field>Reference})
 * @param snapshotFields the fields kept in that snapshot; null when the metadata does not declare them
 */
public record RefSchema(String object, String snapshotField, List<String> snapshotFields) {
}
