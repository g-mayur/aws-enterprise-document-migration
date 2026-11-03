package io.github.awsdocmigration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.awsdocmigration.domain.DocumentRecord;
import java.io.*; import java.nio.file.*; import java.util.*;

/** Reads JSON Lines incrementally; it does not load a full manifest into memory. */
public final class ManifestReader implements Iterable<DocumentRecord> {
    private final Path path; private final ObjectMapper mapper=new ObjectMapper();
    public ManifestReader(Path path) { this.path=path; }
    public Iterator<DocumentRecord> iterator() {
        try { BufferedReader reader=Files.newBufferedReader(path); return new Iterator<>() { String next=advance();
            private String advance() { try { String line; while((line=reader.readLine())!=null) if(!line.isBlank() && !line.stripLeading().startsWith("#")) return line; reader.close(); return null; } catch(IOException e){ throw new UncheckedIOException(e); } }
            public boolean hasNext(){ return next!=null; }
            public DocumentRecord next(){ if(next==null) throw new NoSuchElementException(); try { DocumentRecord r=mapper.readValue(next, DocumentRecord.class); next=advance(); return r; } catch(Exception e){ throw new IllegalArgumentException("Invalid manifest JSONL row",e); } }
        }; } catch(IOException e){ throw new UncheckedIOException(e); }
    }
}
