
package io.github.awsdocmigration.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Checksums {

    private Checksums() {
    }

    public static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }

            return java.util.HexFormat.of().formatHex(md.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
