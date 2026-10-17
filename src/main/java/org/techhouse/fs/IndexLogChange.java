package org.techhouse.fs;

import java.util.List;

public record IndexLogChange(String fileKey, List<String> added, List<String> removed) {
}
