package org.techhouse.cluster;

import java.io.IOException;
import java.util.List;

interface NameListing {
    List<String> names() throws IOException;
}
