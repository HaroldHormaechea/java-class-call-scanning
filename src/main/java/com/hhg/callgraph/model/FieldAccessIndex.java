package com.hhg.callgraph.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FieldAccessIndex {

    private final Map<FieldReference, Set<MethodReference>> readers = new HashMap<>();
    private final Map<FieldReference, Set<MethodReference>> writers = new HashMap<>();

    public void addRead(MethodReference accessor, FieldReference field) {
        readers.computeIfAbsent(field, k -> new HashSet<>()).add(accessor);
    }

    public void addWrite(MethodReference accessor, FieldReference field) {
        writers.computeIfAbsent(field, k -> new HashSet<>()).add(accessor);
    }

    public Set<MethodReference> getReaders(FieldReference field) {
        return Collections.unmodifiableSet(readers.getOrDefault(field, Collections.emptySet()));
    }

    public Set<MethodReference> getWriters(FieldReference field) {
        return Collections.unmodifiableSet(writers.getOrDefault(field, Collections.emptySet()));
    }

    public Set<MethodReference> getAccessors(FieldReference field) {
        Set<MethodReference> result = new HashSet<>();
        result.addAll(readers.getOrDefault(field, Collections.emptySet()));
        result.addAll(writers.getOrDefault(field, Collections.emptySet()));
        return Collections.unmodifiableSet(result);
    }

    public Set<FieldReference> getAllFields() {
        Set<FieldReference> all = new HashSet<>();
        all.addAll(readers.keySet());
        all.addAll(writers.keySet());
        return Collections.unmodifiableSet(all);
    }

    public void mergeFrom(FieldAccessIndex other) {
        for (var entry : other.readers.entrySet()) {
            for (MethodReference reader : entry.getValue()) {
                addRead(reader, entry.getKey());
            }
        }
        for (var entry : other.writers.entrySet()) {
            for (MethodReference writer : entry.getValue()) {
                addWrite(writer, entry.getKey());
            }
        }
    }

    public Set<FieldReference> findByName(String internalClass, String fieldName) {
        Set<FieldReference> result = new HashSet<>();
        for (FieldReference field : getAllFields()) {
            if (field.className().equals(internalClass) && field.fieldName().equals(fieldName)) {
                result.add(field);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Deep copy used by the incremental rebuild path. */
    public FieldAccessIndex copy() {
        FieldAccessIndex clone = new FieldAccessIndex();
        for (Map.Entry<FieldReference, Set<MethodReference>> e : readers.entrySet()) {
            clone.readers.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (Map.Entry<FieldReference, Set<MethodReference>> e : writers.entrySet()) {
            clone.writers.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return clone;
    }

    /**
     * Removes every field whose owner class matches, and every accessor whose owner class
     * matches. Mirrors {@link CallGraph#removeClass} semantics: only the owner-class side
     * is purged; cross-class survival of cleanup happens the next time that class is
     * re-scanned.
     */
    public void removeClass(String internalClassName) {
        if (internalClassName == null) return;
        // 1. Remove every field owned by this class (both maps).
        readers.keySet().removeIf(f -> internalClassName.equals(f.className()));
        writers.keySet().removeIf(f -> internalClassName.equals(f.className()));
        // 2. From the surviving fields, strip accessor methods owned by this class.
        for (Set<MethodReference> set : readers.values()) {
            set.removeIf(m -> internalClassName.equals(m.getClassName()));
        }
        for (Set<MethodReference> set : writers.values()) {
            set.removeIf(m -> internalClassName.equals(m.getClassName()));
        }
        // 3. Drop now-empty entries.
        readers.entrySet().removeIf(e -> e.getValue().isEmpty());
        writers.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
