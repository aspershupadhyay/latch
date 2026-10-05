// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.files

import android.provider.MediaStore
import io.github.aspershupadhyay.latch.protocol.FileLocation
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Regression: asking the Downloads collection for MEDIA_TYPE threw IllegalArgumentException on Android 15, so list_files and write_file in Downloads failed. */
class MediaProjectionTest {
    private val base = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE)

    @Test fun downloadsNeverAsksForMediaType() {
        assertArrayEquals(base, PhoneFiles.mediaProjection(FileLocation.DOWNLOADS, *base))
    }

    @Test fun photosReadsMediaTypeLast() {
        assertArrayEquals(base + MediaStore.Files.FileColumns.MEDIA_TYPE, PhoneFiles.mediaProjection(FileLocation.PHOTOS, *base))
    }
}
