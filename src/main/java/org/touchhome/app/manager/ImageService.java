package org.touchhome.app.manager;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.touchhome.app.repository.ImageRepository;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.ImageEntity;
import org.touchhome.bundle.api.exception.ServerException;
import org.touchhome.bundle.api.util.TouchHomeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.touchhome.bundle.api.util.TouchHomeUtils.resolvePath;

@Log4j2
@Component
@RequiredArgsConstructor
public class ImageService {

    static Path imagesDir = resolvePath("images");

    private final ImageRepository imageRepository;
    private final EntityContext entityContext;

    public ResponseEntity<InputStreamResource> getImage(String fileName) {
        return getImage(imagesDir.resolve(fileName));
    }

    @SneakyThrows
    public ResponseEntity<InputStreamResource> getImage(Path imagePath) {
        return TouchHomeUtils.inputStreamToResource(Files.newInputStream(imagePath), MediaType.parseMediaType(Files.probeContentType(imagePath)));
    }

    public boolean isExistsImage(String imageID) {
        ImageEntity imageEntity = imageRepository.getByEntityID(imageID);
        if (imageEntity != null) {
            if (imageEntity.toPath() != null) {
                Path imagePath = imageEntity.toPath();
                if (Files.exists(imagePath)) {
                    try {
                        String contentType = Files.probeContentType(imagePath);
                        return contentType != null;
                    } catch (IOException ignore) {
                    }
                }
            }
        }
        return false;
    }

    public ImageEntity upload(String entityID, BufferedImage bufferedImage) {
        try {
            Path imagePath = imagesDir.resolve(entityID);
            String ext = entityID.substring(entityID.length() - 3);
            ImageIO.write(bufferedImage, ext, imagePath.toFile());
            ImageEntity imageEntity = imageRepository.getByPath(imagePath.toAbsolutePath().toString());
            if (imageEntity == null) {
                imageEntity = new ImageEntity();
                imageEntity.setPath(imagePath.toAbsolutePath().toString());
            }
            imageEntity.computeEntityID(() -> entityID.substring(0, entityID.length() - ext.length() - 1));
            imageEntity.setOriginalWidth(bufferedImage.getWidth());
            imageEntity.setOriginalHeight(bufferedImage.getHeight());
            return entityContext.save(imageEntity);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new ServerException(e);
        }
    }
}
