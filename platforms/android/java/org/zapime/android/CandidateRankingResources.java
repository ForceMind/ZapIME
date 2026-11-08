// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** APK-pinned, versioned model installation. Used only on the ranking worker. */
final class CandidateRankingResources {
    interface Source { InputStream open(String name) throws IOException; }

    static synchronized File prepare(File parent, Source source) throws IOException {
        requireDirectory(parent);
        String version = CandidateModelCatalog.MODEL_ID + "-" + CandidateModelCatalog.REVISION.substring(0, 16);
        File target = new File(parent, version);
        if (verify(target)) return target;
        File staged = new File(parent, version + ".incoming");
        removeKnownDirectory(staged);
        requireDirectory(staged);
        for (CandidateModelCatalog.Entry entry : CandidateModelCatalog.FILES) {
            File output = new File(staged, entry.name);
            requireChild(output, staged);
            MessageDigest digest = sha256();
            long size = 0;
            try (InputStream in = source.open(entry.name); FileOutputStream out = new FileOutputStream(output)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    if (count == 0) continue;
                    if (size > entry.size - count) throw new IOException("candidate_resource_size");
                    size += count;
                    digest.update(bytes, 0, count);
                    out.write(bytes, 0, count);
                }
                out.getFD().sync();
            }
            if (size != entry.size || !hex(digest.digest()).equals(entry.sha256)) {
                throw new IOException("candidate_resource_hash");
            }
        }
        if (!verify(staged)) throw new IOException("candidate_resource_verify");
        // A valid installation is never touched on a failed staged copy.
        // Only this catalogue's generated paths may be replaced or cleaned.
        removeKnownDirectory(target);
        if (!staged.renameTo(target) || !verify(target)) throw new IOException("candidate_resource_publish");
        return target;
    }

    static boolean verify(File directory) throws IOException {
        if (!directory.exists()) return false;
        requireCanonical(directory);
        if (!directory.isDirectory()) throw new IOException("candidate_resource_directory");
        String[] names = directory.list();
        if (names == null || names.length != CandidateModelCatalog.FILES.size()) return false;
        Set<String> expected = names();
        for (String name : names) if (!expected.remove(name)) return false;
        for (CandidateModelCatalog.Entry entry : CandidateModelCatalog.FILES) {
            File file = new File(directory, entry.name);
            requireChild(file, directory);
            if (!file.isFile() || file.length() != entry.size) return false;
            MessageDigest digest = sha256();
            long size = 0;
            try (InputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = in.read(bytes)) != -1) {
                    if (count == 0) continue;
                    if (size > entry.size - count) return false;
                    size += count;
                    digest.update(bytes, 0, count);
                }
            }
            if (size != entry.size || !hex(digest.digest()).equals(entry.sha256)) return false;
        }
        return true;
    }

    private static void requireDirectory(File directory) throws IOException {
        requireCanonical(directory);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("candidate_resource_directory");
        requireCanonical(directory);
    }

    private static void requireCanonical(File path) throws IOException {
        if (!path.getCanonicalFile().equals(path.getAbsoluteFile())) throw new IOException("candidate_resource_path");
    }

    private static void requireChild(File file, File parent) throws IOException {
        if (!file.getParentFile().equals(parent)) throw new IOException("candidate_resource_path");
        requireCanonical(file);
    }

    private static Set<String> names() throws IOException {
        HashSet<String> names = new HashSet<>();
        for (CandidateModelCatalog.Entry entry : CandidateModelCatalog.FILES) {
            if (!entry.name.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || !names.add(entry.name)
                    || entry.size <= 0 || entry.size > CandidateModelCatalog.TOTAL_BYTES
                    || !entry.sha256.matches("[0-9a-f]{64}")) {
                throw new IOException("candidate_resource_catalog");
            }
        }
        return names;
    }

    private static void removeKnownDirectory(File directory) throws IOException {
        requireCanonical(directory);
        if (!directory.exists()) return;
        if (!directory.isDirectory()) throw new IOException("candidate_resource_directory");
        String[] children = directory.list();
        if (children == null) throw new IOException("candidate_resource_directory");
        Set<String> expected = names();
        // Validate the whole cleanup set before touching any entry.
        for (String name : children) {
            if (!expected.contains(name)) throw new IOException("candidate_resource_extra_file");
            File file = new File(directory, name);
            requireChild(file, directory);
            if (!file.isFile()) throw new IOException("candidate_resource_path");
        }
        for (String name : children) {
            if (!new File(directory, name).delete()) throw new IOException("candidate_resource_cleanup");
        }
        if (!directory.delete()) throw new IOException("candidate_resource_cleanup");
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("candidate_resource_digest"); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 255;
            text.append(Character.forDigit(unsigned >>> 4, 16));
            text.append(Character.forDigit(unsigned & 15, 16));
        }
        return text.toString();
    }

    private CandidateRankingResources() { }
}
