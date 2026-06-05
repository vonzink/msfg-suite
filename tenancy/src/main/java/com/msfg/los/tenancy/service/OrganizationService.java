package com.msfg.los.tenancy.service;
import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.tenancy.domain.Organization;
import com.msfg.los.tenancy.repo.OrganizationRepository;
import com.msfg.los.tenancy.web.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class OrganizationService {
    private final OrganizationRepository orgs;
    public OrganizationService(OrganizationRepository orgs) { this.orgs = orgs; }

    @Transactional
    public Organization create(CreateOrgRequest req) {
        if (orgs.existsBySlug(req.slug())) throw new ConflictException("slug already in use: " + req.slug());
        Organization o = new Organization();
        o.setName(req.name());
        o.setSlug(req.slug());
        return orgs.save(o);
    }
    @Transactional(readOnly = true)
    public Organization get(UUID id) {
        return orgs.findById(id).orElseThrow(() -> new NotFoundException("Organization", id));
    }
    @Transactional(readOnly = true)
    public Page<Organization> list(Pageable pageable) { return orgs.findAll(pageable); }

    @Transactional
    public Organization update(UUID id, UpdateOrgRequest req) {
        Organization o = get(id);
        if (req.name() != null) o.setName(req.name());
        if (req.status() != null) o.setStatus(req.status());
        if (req.settings() != null) o.setSettings(req.settings());
        return o;
    }
}
