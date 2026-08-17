package com.remoteboxjava;

import com.sun.jna.platform.win32.Crypt32Util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Stores a secret in the application settings without writing it in clear text.
 *
 * <p>On Windows the value is encrypted with DPAPI, so only the current user
 * account on this machine can read it back. Everywhere else nothing is stored:
 * an obfuscated password in a readable file would only pretend to be safe.</p>
 */
final class SecretStore {
    private static final Logger LOG = LogManager.getLogger(SecretStore.class);

    private SecretStore() {
    }

    static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** @return the encrypted secret, or an empty string when it cannot be protected */
    static String protect(char[] secret) {
        if (secret == null || secret.length == 0 || !isSupported()) {
            return "";
        }
        byte[] plain = toBytes(secret);
        try {
            return Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain));
        } catch (Throwable failure) {
            LOG.warn("Could not encrypt the password, so it is not stored.", failure);
            return "";
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** @return the decrypted secret, or an empty array when there is none to read */
    static char[] reveal(String stored) {
        if (stored == null || stored.isBlank() || !isSupported()) {
            return new char[0];
        }
        byte[] plain = null;
        try {
            plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored));
            return toChars(plain);
        } catch (Throwable failure) {
            // A settings file copied from another account or machine cannot be decrypted.
            LOG.warn("Could not decrypt the stored password; it has to be entered again.", failure);
            return new char[0];
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static byte[] toBytes(char[] secret) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        Arrays.fill(encoded.array(), (byte) 0);
        return bytes;
    }

    private static char[] toChars(byte[] plain) {
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
        char[] characters = new char[decoded.remaining()];
        decoded.get(characters);
        Arrays.fill(decoded.array(), '\0');
        return characters;
    }
}
