package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLimitRunCluster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLimitRunClusterRepository extends JpaRepository<Track1PurchaseLimitRunCluster, UUID> {
    List<Track1PurchaseLimitRunCluster> findByRunIdIn(Collection<UUID> runIds);
}
