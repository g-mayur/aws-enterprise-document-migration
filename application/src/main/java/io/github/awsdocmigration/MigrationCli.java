package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.connector.filesystem.FilesystemSource;
import io.github.awsdocmigration.connector.s3.S3Source;
import io.github.awsdocmigration.domain.*;
import io.github.awsdocmigration.engine.*;
import io.github.awsdocmigration.storage.*;
import io.github.awsdocmigration.util.Checksums;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.io.*; import java.nio.file.*; import java.util.*;

/** Minimal reference CLI: manifest-driven transfer from filesystem or S3 to S3. */
public final class MigrationCli {
    public static void main(String[] args) throws Exception {
        Map<String,String> a=parse(args);
        String sourceType=req(a,"source-type"); String bucket=req(a,"destination-bucket");
        String region=a.getOrDefault("region", System.getenv().getOrDefault("AWS_REGION","us-east-1"));
        VersionPolicy policy=VersionPolicy.valueOf(a.getOrDefault("version-policy","ALL_VERSIONS"));
        Path manifest=Path.of(req(a,"manifest"));
        try(S3Client s3=S3Client.builder().region(Region.of(region)).build()) {
            DocumentSource source=switch(sourceType) {
                case "filesystem" -> new FilesystemSource(Path.of(req(a,"source-root")));
                case "s3" -> new S3Source(s3,req(a,"source-bucket"),a.getOrDefault("source-prefix",""));
                default -> throw new IllegalArgumentException("Unsupported source-type: "+sourceType);
            };
            source.validateConnection(); DestinationWriter writer=new S3DestinationWriter(s3,bucket); DestinationKeyResolver keys=new DestinationKeyResolver();
            long ok=0,failed=0;
            for(DocumentRecord record:new ManifestReader(manifest)) {
                try(InputStream input=source.openStream(record)) {
                    // Hash into a temporary file so content can be streamed again to the synchronous S3 SDK writer.
                    Path temp=Files.createTempFile("migration-", ".bin");
                    try { Files.copy(input,temp,StandardCopyOption.REPLACE_EXISTING); String digest; try(InputStream verify=Files.newInputStream(temp)){ digest=Checksums.sha256(verify); }
                        if(record.expectedSha256()!=null && !record.expectedSha256().equalsIgnoreCase(digest)) throw new IOException("SHA-256 mismatch for "+record.idempotencyKey());
                        try(InputStream upload=Files.newInputStream(temp)){ writer.write(record,keys.resolve(record,policy),upload,digest); }
                    } finally { Files.deleteIfExists(temp); }
                    ok++;
                } catch(Exception e) { failed++; System.err.println("FAILED "+record.idempotencyKey()+": "+e.getMessage()); }
            }
            source.close(); System.out.printf("Migration run finished. succeeded=%d failed=%d%n",ok,failed); if(failed>0) System.exit(2);
        }
    }
    private static Map<String,String> parse(String[] args){ Map<String,String> m=new HashMap<>(); for(int i=0;i<args.length;i++){ if(!args[i].startsWith("--") || i+1>=args.length) throw new IllegalArgumentException("Expected --name value arguments"); m.put(args[i].substring(2),args[++i]); } return m; }
    private static String req(Map<String,String> m,String k){ String v=m.get(k); if(v==null||v.isBlank()) throw new IllegalArgumentException("Missing --"+k); return v; }
}
