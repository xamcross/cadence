package com.cadence.service;

import com.cadence.api.WorkspaceDtos;
import com.cadence.api.WorkspaceExceptions;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.domain.WorkspaceLogo;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.repository.WorkspaceLogoRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;

/**
 * Branding (F03 US3): logo validation/storage and the public brand resolution. Logo validation runs
 * in the D6 order — size, then magic byte (raster allow-list, SVG rejected), then header-only
 * dimensions BEFORE a bounded decode (so a decompression bomb cannot OOM the JVM). The public
 * resolution returns per-attribute defaults (research D5).
 *
 * Single-workspace MVP: the public reads resolve "the" workspace (the singleton config/logo doc);
 * the Admin writes are scoped to the caller's workspaceId.
 */
@Service
public class BrandingService {

    static final int LOGO_MAX_BYTES = 1024 * 1024; // 1 MB
    static final int DIM_MAX = 2048;
    static final String DEFAULT_COLOR = "#1F2937";
    private static final String LOGO_URL = "/api/public/workspace/logo";

    private final MongoTemplate mongo;
    private final WorkspaceLogoRepository logos;
    private final WorkspaceConfigRepository configs;
    private final AuthAuditService audit;
    private final Clock clock;

    private volatile byte[] defaultLogoPng;

    public BrandingService(MongoTemplate mongo, WorkspaceLogoRepository logos,
                           WorkspaceConfigRepository configs, AuthAuditService audit, Clock clock) {
        this.mongo = mongo;
        this.logos = logos;
        this.configs = configs;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Admin writes (scoped to the caller's workspace) -------------------------------------

    public void uploadLogo(String workspaceId, String actorMemberId, byte[] bytes, String declaredContentType) {
        requireConfigured(workspaceId);
        String verifiedType = validateLogo(bytes);
        WorkspaceLogo logo = logos.findByWorkspaceId(workspaceId).orElseGet(WorkspaceLogo::new);
        logo.setWorkspaceId(workspaceId);
        logo.setBytes(bytes);
        logo.setContentType(verifiedType);
        logo.setSize(bytes.length);
        logo.setUpdatedAt(Instant.now(clock));
        logos.save(logo);
        mongo.updateFirst(byWorkspace(workspaceId),
            new Update().set("hasLogo", true).set("updatedAt", Instant.now(clock)), WorkspaceConfig.class);
        audit.configChanged(workspaceId, actorMemberId, "logo", null, null);
    }

    public void deleteLogo(String workspaceId, String actorMemberId) {
        requireConfigured(workspaceId);
        logos.deleteByWorkspaceId(workspaceId);
        mongo.updateFirst(byWorkspace(workspaceId),
            new Update().set("hasLogo", false).set("updatedAt", Instant.now(clock)), WorkspaceConfig.class);
        audit.configChanged(workspaceId, actorMemberId, "logo", null, null);
    }

    // --- public reads (singleton workspace) --------------------------------------------------

    public WorkspaceDtos.BrandingResponse resolvePublicBranding() {
        String color = configs.findAll().stream().findFirst()
            .map(WorkspaceConfig::getBrandColor)
            .filter(c -> c != null && !c.isBlank())
            .orElse(DEFAULT_COLOR);
        return new WorkspaceDtos.BrandingResponse(color, LOGO_URL);
    }

    /** The stored logo, or the generated default placeholder when none is set. */
    public Logo resolvePublicLogo() {
        // limit(1): single-workspace MVP — never load more than one logo blob into memory.
        WorkspaceLogo stored = mongo.findOne(new Query().limit(1), WorkspaceLogo.class);
        return stored != null
            ? new Logo(stored.getBytes(), stored.getContentType())
            : new Logo(defaultLogo(), "image/png");
    }

    private void requireConfigured(String workspaceId) {
        if (!configs.existsByWorkspaceIdAndConfiguredAtNotNull(workspaceId)) {
            throw new WorkspaceExceptions.NotConfiguredException();
        }
    }

    public record Logo(byte[] bytes, String contentType) {}

    // --- validation (research D6) ------------------------------------------------------------

    /** Returns the verified content type ("image/png"|"image/jpeg") or throws InvalidLogoException. */
    String validateLogo(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new WorkspaceExceptions.InvalidLogoException("The logo file is empty.");
        }
        if (bytes.length > LOGO_MAX_BYTES) {
            throw new WorkspaceExceptions.InvalidLogoException("The logo must be 1 MB or smaller.");
        }
        String type = magicType(bytes); // rejects SVG and everything non-raster
        if (type == null) {
            throw new WorkspaceExceptions.InvalidLogoException("Only PNG or JPEG images are accepted.");
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new WorkspaceExceptions.InvalidLogoException("The logo could not be read.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new WorkspaceExceptions.InvalidLogoException("The logo is not a valid image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                // Header-only dimensions BEFORE decode — defeats the decompression bomb (D6 gate 3).
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w > DIM_MAX || h > DIM_MAX) {
                    throw new WorkspaceExceptions.InvalidLogoException(
                        "The logo dimensions must be at most " + DIM_MAX + "x" + DIM_MAX + ".");
                }
                BufferedImage img = reader.read(0); // bounded decode
                if (img == null) {
                    throw new WorkspaceExceptions.InvalidLogoException("The logo is not a valid image.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof WorkspaceExceptions.InvalidLogoException ile) {
                throw ile;
            }
            throw new WorkspaceExceptions.InvalidLogoException("The logo is not a valid image.");
        }
        return type;
    }

    /** Inspect actual leading bytes (NOT the client content-type). Returns null if not PNG/JPEG. */
    private static String magicType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
            && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    private byte[] defaultLogo() {
        byte[] cached = defaultLogoPng;
        if (cached != null) {
            return cached;
        }
        try {
            int rgb = Integer.parseInt(DEFAULT_COLOR.substring(1), 16);
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    img.setRGB(x, y, rgb);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            cached = out.toByteArray();
            defaultLogoPng = cached;
            return cached;
        } catch (IOException e) {
            throw new IllegalStateException("default logo generation failed", e);
        }
    }

    private static Query byWorkspace(String workspaceId) {
        return new Query(Criteria.where("workspaceId").is(workspaceId));
    }
}
