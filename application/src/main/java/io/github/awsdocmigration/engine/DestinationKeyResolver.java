package io.github.awsdocmigration.engine;
import io.github.awsdocmigration.domain.*;
import java.net.URLEncoder; import java.nio.charset.StandardCharsets;
public final class DestinationKeyResolver {
    public String resolve(DocumentRecord r, VersionPolicy policy) {
        if (policy == VersionPolicy.LATEST_ONLY && !r.isLatest()) throw new IllegalArgumentException("LATEST_ONLY requires the source-authoritative latest version in the manifest");
        String id=encode(r.documentId()); String version=encode(r.versionId()); String name=encode(r.fileName());
        return policy == VersionPolicy.LATEST_ONLY ? "documents/"+id+"/current/"+name : "documents/"+id+"/versions/"+version+"/"+name;
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
