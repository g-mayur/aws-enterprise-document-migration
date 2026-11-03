package io.github.awsdocmigration.util;
import java.io.*; import java.security.*;
public final class Checksums { private Checksums() {}
    public static String sha256(InputStream in) throws IOException {
        try { MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[1024*1024]; int n; while((n=in.read(b))!=-1) md.update(b,0,n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
