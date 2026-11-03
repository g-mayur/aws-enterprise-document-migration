package io.github.awsdocmigration;
import io.github.awsdocmigration.domain.*; import io.github.awsdocmigration.engine.*; import org.junit.jupiter.api.Test; import java.util.Map; import static org.junit.jupiter.api.Assertions.*;
class DestinationKeyResolverTest {
 @Test void latestKeyRequiresLatestFlag(){ var r=new DocumentRecord("doc/1","v1","a.pdf","a.pdf",10,null,false,Map.of()); assertThrows(IllegalArgumentException.class,()->new DestinationKeyResolver().resolve(r,VersionPolicy.LATEST_ONLY)); }
 @Test void allVersionsAreSeparated(){ var r=new DocumentRecord("doc1","v2","a.pdf","a.pdf",10,null,false,Map.of()); assertTrue(new DestinationKeyResolver().resolve(r,VersionPolicy.ALL_VERSIONS).contains("/versions/v2/")); }
}
