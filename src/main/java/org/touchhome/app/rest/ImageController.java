package org.touchhome.app.rest;

import lombok.RequiredArgsConstructor;
import net.rossillo.spring.web.mvc.CacheControl;
import net.rossillo.spring.web.mvc.CachePolicy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.touchhome.app.manager.ImageService;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.ImageEntity;

@RestController
@RequestMapping("/rest/image")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;
    private final EntityContext entityContext;

    @GetMapping("/{imagePath:.+}")
    @CacheControl(maxAge = 3600, policy = CachePolicy.PUBLIC)
    public ResponseEntity<InputStreamResource> getImage(@PathVariable String imagePath) {
        ImageEntity imageEntity = entityContext.getEntity(imagePath);
        if (imageEntity != null) {
            return getImage(imageEntity.toPath().toString());
        } else {
            return imageService.getImage(imagePath);
        }
    }
}
