package io.github.awsdocmigration.storage;
import io.github.awsdocmigration.domain.DocumentRecord;
import java.io.InputStream;
public interface DestinationWriter { void write(DocumentRecord record, String key, InputStream content, String sha256) throws Exception; }
