package com.cellead.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;

class AttachmentApplicationTest {
    @TempDir Path temp;
    @Test void storedFileHasContentAndSha256() throws Exception {
        AttachmentStorage storage=new AttachmentStorage(temp.toString());
        StoredFile stored=storage.save(new MockMultipartFile("file","proof.txt","text/plain","evidence".getBytes()));
        assertThat(stored.sha256()).hasSize(64);
        assertThat(storage.load(stored.key()).contentLength()).isEqualTo(8);
        storage.delete(stored.key());
        assertThat(Files.list(temp)).isEmpty();
    }
}
