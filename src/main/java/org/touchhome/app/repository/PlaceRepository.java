package org.touchhome.app.repository;

import org.springframework.stereotype.Repository;
import org.touchhome.bundle.api.entity.PlaceEntity;
import org.touchhome.bundle.api.repository.AbstractRepository;

@Repository("placeRepository")
public class PlaceRepository extends AbstractRepository<PlaceEntity> {

    public PlaceRepository() {
        super(PlaceEntity.class);
    }

}



