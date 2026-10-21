package com.example.splitease.data;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id v1.3; no provider registration and no plaintext persisted or returned. */
final class PasswordHasher {
    static final String ALGORITHM = "argon2id-v19";
    static final int ITERATIONS = 2, MEMORY_KIB = 19456, PARALLELISM = 1;
    static byte[] salt() { byte[] value = new byte[16]; new SecureRandom().nextBytes(value); return value; }
    static byte[] hash(char[] password, byte[] salt, int iterations, int memory, int parallelism) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("Password required");
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                .withIterations(iterations).withMemoryAsKB(memory).withParallelism(parallelism).build();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            byte[] hash = new byte[32];
            generator.generateBytes(password, hash);
            return hash;
        } finally { params.clear(); }
    }
    static boolean verify(char[] password, byte[] salt, byte[] expected, int iterations, int memory, int parallelism) {
        byte[] actual = hash(password, salt, iterations, memory, parallelism);
        try { return MessageDigest.isEqual(expected, actual); }
        finally { Arrays.fill(actual, (byte) 0); }
    }
}
