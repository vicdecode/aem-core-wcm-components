/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2017 Adobe
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package com.adobe.cq.wcm.core.components.internal.servlets;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.util.Text;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.commons.metrics.Timer;
import org.apache.sling.commons.mime.MimeTypeService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.wcm.core.components.internal.models.v1.AbstractImageDelegatingModel;
import com.adobe.cq.wcm.core.components.internal.resource.CoreResourceWrapper;
import com.adobe.cq.wcm.core.components.models.Image;
import com.adobe.cq.wcm.core.components.util.ComponentUtils;
import com.day.cq.commons.DownloadResource;
import com.day.cq.commons.ImageResource;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.dam.api.Rendition;
import com.day.cq.dam.api.handler.AssetHandler;
import com.day.cq.dam.api.handler.store.AssetStore;
import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.components.ComponentManager;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;
import com.day.cq.wcm.foundation.WCMRenditionPicker;
import com.day.image.Layer;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Servlet for adaptive images, can render images with different widths based on policies and requested width.
 * <p>
 * Registration of the servlet is handled by the {@link AdaptiveImageServletMappingConfigurationConsumer}
 * based on {@link AdaptiveImageServletMappingConfigurationFactory} configurations.
 * <p>
 * The following configurations are provided out-of-box for {@code ['jpg','jpeg','gif','png','svg']} extensions:
 * <ul>
 * <li>{@code RTs=['core/wcm/components/image'], selectors=['img']} - for Image v1 URLs</li>
 * <li>{@code RTs=['core/wcm/components/image','cq/Page'], selectors=['coreimg']} - for Image v2 URLs</li>
 * </ul>
 */
public class AdaptiveImageServlet extends SlingSafeMethodsServlet {

    public static final String DEFAULT_SELECTOR = "img";
    public static final String CORE_DEFAULT_SELECTOR = "coreimg";
    private static final String IMAGE_RESOURCE_TYPE = "core/wcm/components/image";
    static final int DEFAULT_RESIZE_WIDTH = 1280;
    public static final int DEFAULT_JPEG_QUALITY = 82; // similar to what is the default in com.day.image.Layer#write(...)
    public static final int DEFAULT_MAX_SIZE = 3840; // 4K UHD width
    private static final Logger LOGGER = LoggerFactory.getLogger(AdaptiveImageServlet.class);
    private static final String DEFAULT_MIME = "image/jpeg";
    private static final String SELECTOR_QUALITY_KEY = "quality";
    private static final String SELECTOR_WIDTH_KEY = "width";
    private int defaultResizeWidth;
    private int maxInputWidth;
    
    private AdaptiveImageServletMetrics metrics;

    @SuppressFBWarnings(justification = "This field needs to be transient")
    private transient MimeTypeService mimeTypeService;

    @SuppressFBWarnings(justification = "This field needs to be transient")
    private transient AssetStore assetStore;

    public AdaptiveImageServlet(MimeTypeService mimeTypeService, AssetStore assetStore, AdaptiveImageServletMetrics metrics, 
            int defaultResizeWidth, int maxInputWidth) {
        this.mimeTypeService = mimeTypeService;
        this.assetStore = assetStore;
        this.metrics = metrics;
        this.defaultResizeWidth = defaultResizeWidth > 0 ? defaultResizeWidth : DEFAULT_RESIZE_WIDTH;
        this.maxInputWidth = maxInputWidth > 0 ? maxInputWidth : DEFAULT_MAX_SIZE;
    }

    @Override
    protected void doGet(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response) throws IOException {
        Timer.Context requestDuration = metrics.startDurationRecording();
        try {
            metrics.markServletInvocation();
            RequestPathInfo requestPathInfo = request.getRequestPathInfo();
            List<String> selectorList = selectorToList(requestPathInfo.getSelectorString());
            String suffix = requestPathInfo.getSuffix();
            String imageName = StringUtils.isNotEmpty(suffix) ? FilenameUtils.getName(suffix) : "";

            if (StringUtils.isNotEmpty(suffix)) {
                String suffixExtension = FilenameUtils.getExtension(suffix);
                if (StringUtils.isNotEmpty(suffixExtension)) {
                    if (!suffixExtension.equals(requestPathInfo.getExtension())) {
                        LOGGER.error("The suffix part defines a different extension than the request: {}.", suffix);
                        metrics.markImageErrors();
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    }
                } else {
                    LOGGER.error("Invalid suffix: {}.", suffix);
                    metrics.markImageErrors();
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            }
            Resource component = request.getResource();
            ResourceResolver resourceResolver = request.getResourceResolver();
            if (!component.isResourceType(IMAGE_RESOURCE_TYPE)) {
                // image coming from template; need to switch resource
                Resource componentCandidate = null;
                PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
                if (pageManager != null) {
                    Page page = pageManager.getContainingPage(component);
                    if (page != null) {
                        Template template = page.getTemplate();
                        if (template != null) {
                            if (StringUtils.isNotEmpty(suffix)) {
                                long lastModifiedSuffix = getRequestLastModifiedSuffix(suffix);
                                String relativeTemplatePath = lastModifiedSuffix == 0 ?
                                        // no timestamp info, but extension is valid; get resource name
                                        suffix.substring(0, suffix.lastIndexOf('.')) :
                                        // timestamp info, get parent path from suffix
                                        suffix.substring(0, suffix.lastIndexOf("/" + String.valueOf(lastModifiedSuffix)));
                                String imagePath = ResourceUtil.normalize(template.getPath() + relativeTemplatePath);
                                if (StringUtils.isNotEmpty(imagePath) && !template.getPath().equals(imagePath)) {
                                    componentCandidate = resourceResolver.getResource(imagePath);
                                }
                            }
                        }
                    }
                }
                if (componentCandidate == null) {
                    LOGGER.error("Unable to retrieve an image from this page's template.");
                    metrics.markImageErrors();
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                component = componentCandidate;
            }

            ImageComponent imageComponent = new ImageComponent(component);
            // If the image has no content, use the featured image of the page if it exists
            if (imageComponent.source == Source.NOCONTENT) {
                PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
                if (pageManager != null) {
                    Page page = pageManager.getContainingPage(component);
                    if (page != null) {
                        Resource featuredImage = ComponentUtils.getFeaturedImage(page);
                        if (featuredImage != null) {
                            imageComponent = new ImageComponent(featuredImage);
                        }
                    }
                }
            }
            if (imageComponent.source == Source.NOCONTENT || imageComponent.source == Source.NONEXISTING) {
                LOGGER.error("Either the image from {} does not have a valid file reference" +
                        "or the containing page does not have a valid featured image", component.getPath());
                metrics.markImageErrors();
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            ValueMap componentProperties = component.getValueMap();
            long lastModifiedEpoch = 0;
            Calendar lastModifiedDate = componentProperties.get(JcrConstants.JCR_LASTMODIFIED, Calendar.class);
            if (lastModifiedDate == null) {
                lastModifiedDate = componentProperties.get(NameConstants.PN_PAGE_LAST_MOD, Calendar.class);
            }
            if (lastModifiedDate != null) {
                lastModifiedEpoch = lastModifiedDate.getTimeInMillis();
            }
            Asset asset = null;
            if (imageComponent.source == Source.ASSET) {
                asset = imageComponent.imageResource.adaptTo(Asset.class);
                if (asset == null) {
                    LOGGER.error("Unable to adapt resource {} used by image {} to an asset.", imageComponent.imageResource.getPath(),
                            component.getPath());
                    metrics.markImageErrors();
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                long assetLastModifiedEpoch = asset.getLastModified();
                if (assetLastModifiedEpoch > lastModifiedEpoch) {
                    lastModifiedEpoch = assetLastModifiedEpoch;
                }
            }
            long requestLastModifiedSuffix = getRequestLastModifiedSuffix(suffix);
            if (requestLastModifiedSuffix >= 0 && requestLastModifiedSuffix != lastModifiedEpoch) {
                String redirectLocation = getRedirectLocation(request, lastModifiedEpoch);
                if (StringUtils.isNotEmpty(redirectLocation)) {
                    response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
                    response.setHeader("Location", redirectLocation);
                    return;
                } else {
                    LOGGER.error("Unable to determine correct redirect location.");
                    metrics.markImageErrors();
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            }
            if (!handleIfModifiedSinceHeader(request, response, lastModifiedEpoch)) {

                Map<String, Integer> transformationMap = getTransformationMap(selectorList, component);
                Integer jpegQualityInPercentage = transformationMap.get(SELECTOR_QUALITY_KEY);
                double quality = jpegQualityInPercentage / 100.0d;
                int resizeWidth = transformationMap.get(SELECTOR_WIDTH_KEY);
                String imageType = getImageType(requestPathInfo.getExtension());

                if (imageComponent.source == Source.FILE) {
                    transformAndStreamFile(response, componentProperties, resizeWidth, quality,
                            imageComponent.imageResource, imageType, imageName);
                } else if (imageComponent.source == Source.ASSET) {
                    transformAndStreamAsset(response, componentProperties, resizeWidth, quality, asset, imageType,
                            imageName);
                }
                metrics.markImageStreamed();
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid image request", e.getMessage());
            metrics.markImageErrors();
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } finally {
            requestDuration.stop();
        }

    }

    @Nullable
    private String getRedirectLocation(SlingHttpServletRequest request, long lastModifiedEpoch) {
        RequestPathInfo requestPathInfo = request.getRequestPathInfo();
        if (request.getResource().isResourceType(IMAGE_RESOURCE_TYPE)) {
            return Joiner.on('.').join(Text.escapePath(request.getContextPath() + requestPathInfo.getResourcePath()),
                    requestPathInfo.getSelectorString(), requestPathInfo.getExtension() + "/" + lastModifiedEpoch,
                    requestPathInfo.getExtension());
        }
        long lastModifiedSuffix = getRequestLastModifiedSuffix(request.getPathInfo());
        String resourcePath = lastModifiedSuffix > 0 ? ResourceUtil.getParent(request.getPathInfo()) : request.getPathInfo();
        String extension = FilenameUtils.getExtension(resourcePath);
        if (StringUtils.isNotEmpty(resourcePath)) {
            if (StringUtils.isNotEmpty(extension)) {
                resourcePath = resourcePath.substring(0, resourcePath.length() - extension.length() - 1);
            }
            return request.getContextPath() + Text.escapePath(resourcePath) + "/" + lastModifiedEpoch + "." +
                    requestPathInfo.getExtension();
        }
        return null;
    }

    private void transformAndStreamAsset(SlingHttpServletResponse response, ValueMap componentProperties, int resizeWidth, double quality,
                                         Asset asset, String
                                                 imageType, String imageName) throws IOException {
        String extension = mimeTypeService.getExtension(imageType);
        if ("gif".equalsIgnoreCase(extension) || "svg".equalsIgnoreCase(extension)) {
            LOGGER.debug("GIF or SVG asset detected; will render the original rendition.");
            stream(response, asset.getOriginal().getStream(), imageType, imageName);
            return;
        }
        int rotationAngle = getRotation(componentProperties);
        Rectangle rectangle = getCropRect(componentProperties);
        boolean flipHorizontally = componentProperties.get(Image.PN_FLIP_HORIZONTAL, Boolean.FALSE);
        boolean flipVertically = componentProperties.get(Image.PN_FLIP_VERTICAL, Boolean.FALSE);
        if (rotationAngle != 0 || rectangle != null || resizeWidth > 0 || flipHorizontally || flipVertically) {
            int originalWidth = getDimension(asset.getMetadataValue(DamConstants.TIFF_IMAGEWIDTH));
            int originalHeight = getDimension(asset.getMetadataValue(DamConstants.TIFF_IMAGELENGTH));
            Layer layer = null;
            boolean appliedTransformation = false;
            if (rectangle != null) {
                double scaling;
                EnhancedRendition wcmRendition = getWCMRendition(asset);
                double renditionWidth;
                Dimension renditionDimension = wcmRendition.getDimension();
                if (renditionDimension != null) {
                    renditionWidth = renditionDimension.getWidth();
                } else {
                    renditionWidth = originalWidth;
                }
                if (originalWidth > renditionWidth) {
                    scaling = (double) originalWidth / renditionWidth;
                } else {
                    if (originalWidth > 0 ) {
                        scaling = renditionWidth / originalWidth;
                    } else {
                        scaling = 1.0;
                    }
                }
                layer = getLayer(getOriginal(asset));
                if (Math.abs(scaling - 1.0D) != 0) {
                    Rectangle scaledRectangle = new Rectangle(
                            (int) (rectangle.x * scaling),
                            (int) (rectangle.y * scaling),
                            (int) (rectangle.getWidth() * scaling),
                            (int) (rectangle.getHeight() * scaling)
                    );
                    layer.crop(scaledRectangle);
                } else {
                    layer.crop(rectangle);
                }
                appliedTransformation = true;
            }
            if (rotationAngle != 0) {
                if (layer == null) {
                    layer = getLayer(getBestRendition(asset, resizeWidth));
                }
                layer.rotate(rotationAngle);
                LOGGER.debug("Applied rotation transformation ({} degrees).", rotationAngle);
                appliedTransformation = true;
            }
            if (flipHorizontally) {
                if (layer == null) {
                    layer = getLayer(getBestRendition(asset, resizeWidth));
                }
                layer.flipHorizontally();
                LOGGER.debug("Flipped image horizontally.");
                appliedTransformation = true;
            }
            if (flipVertically) {
                if (layer == null) {
                    layer = getLayer(getBestRendition(asset, resizeWidth));
                }
                layer.flipVertically();
                LOGGER.debug("Flipped image vertically.");
                appliedTransformation = true;
            }
            if (!appliedTransformation) {
                EnhancedRendition rendition = getBestRendition(asset, resizeWidth);
                Dimension dimension = rendition.getDimension();
                if (dimension != null) {
                    // keeping aspect ratio
                    originalHeight = Math.round(originalHeight * (dimension.width / (float)originalWidth));
                    originalWidth = dimension.width;
                }
                if (originalWidth > resizeWidth) {
                    int resizeHeight = calculateResizeHeight(originalWidth, originalHeight, resizeWidth);
                    if (resizeHeight > 0 && resizeHeight != originalHeight) {
                        layer = getLayer(rendition);
                        if (layer.getBackground().getTransparency() != Transparency.OPAQUE &&
                                ("jpg".equalsIgnoreCase(extension) || "jpeg".equalsIgnoreCase(extension))) {
                            LOGGER.debug("Adding default (white) background to a transparent PNG: {}/{}", asset.getPath(),
                                    rendition.getName());
                            layer.setBackground(Color.white);
                        }
                        layer.resize(resizeWidth, resizeHeight);
                        response.setContentType(imageType);
                        LOGGER.debug("Resizing asset {}/{} to requested width of {}px; rendering.",asset.getPath(), rendition.getName(), resizeWidth);
                        layer.write(imageType, quality, response.getOutputStream());
                    } else {
                        LOGGER.debug("Found rendition {}/{} has a width of {}px and does not require a resize for requested width of {}px",
                            asset.getPath(), rendition.getName(), dimension != null ? dimension.getWidth() : null, resizeWidth);
                        stream(response, rendition.getStream(), imageType, imageName);
                    }
                } else {
                    LOGGER.debug("Found rendition {}/{} has a width of {}px and does not require a resize for requested width of {}px",
                            asset.getPath(), rendition.getName(), dimension != null ? dimension.getWidth() : null, resizeWidth);
                    stream(response, rendition.getStream(), imageType, imageName);
                }
            } else {
                resizeAndStreamLayer(response, layer, imageType, resizeWidth, quality);
            }
        } else {
            LOGGER.debug("No need to perform any processing on asset {}; rendering.", asset.getPath());
            stream(response, getOriginal(asset).getStream(), imageType, imageName);
        }
    }

    private void transformAndStreamFile(SlingHttpServletResponse response, ValueMap componentProperties, int
            resizeWidth, double quality, Resource imageFile, String imageType, String imageName) throws
            IOException {
        InputStream is = null;
        try {
            is = imageFile.adaptTo(InputStream.class);
            if ("gif".equalsIgnoreCase(mimeTypeService.getExtension(imageType))
                    || "svg".equalsIgnoreCase(mimeTypeService.getExtension(imageType))) {
                LOGGER.debug("GIF or SVG file detected; will render the original file.");
                if (is != null) {
                    stream(response, is, imageType, imageName);
                }
                return;
            }
            int rotationAngle = getRotation(componentProperties);
            Rectangle rectangle = getCropRect(componentProperties);
            boolean flipHorizontally = componentProperties.get(Image.PN_FLIP_HORIZONTAL, Boolean.FALSE);
            boolean flipVertically = componentProperties.get(Image.PN_FLIP_VERTICAL, Boolean.FALSE);
            if (is != null) {
                if (rotationAngle != 0 || rectangle != null || resizeWidth > 0 || flipHorizontally || flipVertically) {
                    Layer layer = new Layer(is);
                    if (rectangle != null) {
                        layer.crop(rectangle);
                        LOGGER.debug("Applied cropping transformation.");
                    }
                    if (rotationAngle != 0) {
                        layer.rotate(rotationAngle);
                        LOGGER.debug("Applied rotation transformation ({} degrees).", rotationAngle);
                    }
                    if (flipHorizontally) {
                        layer.flipHorizontally();
                    }
                    if (flipVertically) {
                        layer.flipVertically();
                    }
                    if (layer.getBackground().getTransparency() != Transparency.OPAQUE &&
                            ("jpg".equalsIgnoreCase(mimeTypeService.getExtension(imageType))
                                    || "jpeg".equalsIgnoreCase(mimeTypeService.getExtension(imageType)))) {
                        LOGGER.debug("Adding default (white) background to a transparent JPG: {}", imageFile.getPath());
                        layer.setBackground(Color.white);
                    }
                    resizeAndStreamLayer(response, layer, imageType, resizeWidth, quality);
                } else {
                    LOGGER.debug("No need to perform any processing on file {}; rendering.", imageFile.getPath());
                    stream(response, is, imageType, imageName);
                }
            }
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    /**
     * Given a {@link Layer}, this method will attempt to resize it proportionally given the supplied {@code resizeWidth}. If the resize
     * operation would result in up-scaling, then the layer is rendered without any resize operation applied.
     *
     * @param response    the response
     * @param layer       the layer
     * @param imageType   the mime type of the image represented by the {@code layer}
     * @param resizeWidth the resize width
     * @throws IOException if the streaming of the {@link Layer} into the response's output stream cannot be performed
     */
    private void resizeAndStreamLayer(SlingHttpServletResponse response, Layer layer, String imageType, int resizeWidth, double quality)
            throws IOException {
        int width = layer.getWidth();
        int height = layer.getHeight();
        int resizeHeight = calculateResizeHeight(width, height, resizeWidth);
        if (resizeHeight > 0) {
            layer.resize(resizeWidth, resizeHeight);
            response.setContentType(imageType);
            LOGGER.debug("Resizing processed (cropped and/or rotated) layer from its current width of {}px to {}px.", width, resizeWidth);
            layer.write(imageType, quality, response.getOutputStream());
        } else {
            response.setContentType(imageType);
            LOGGER.debug("No need to resize processed (cropped and/or rotated) layer since it would lead to upscaling; rendering.");
            layer.write(imageType, quality, response.getOutputStream());
        }
    }

    /**
     * Return a {@link Layer} based on the provided {@link EnhancedRendition}. Ensures the proper asset handler is
     * being used, based on rendition mime type.
     *
     * @param rendition - the rendition
     * @return a layer for the rendition
     * @throws IOException if a {@link Layer} cannot be created for the given rendition
     */
    @NotNull
    private Layer getLayer(@NotNull EnhancedRendition rendition) throws IOException {
        AssetHandler assetHandler = assetStore.getAssetHandler(rendition.getMimeType());
        return new Layer(assetHandler.getImage(rendition.getRendition()));
    }

    /**
     * Given an {@link Asset}, this method will return the WCM rendition (cq5dam.web.*)
     *
     * @param asset the asset for which to retrieve the web rendition
     * @return the WCM rendition, if found the original
     */
    @NotNull
    private EnhancedRendition getWCMRendition(@NotNull Asset asset) {
        return new EnhancedRendition(asset.getRendition(new WCMRenditionPicker()));
    }

    /**
     * Given an {@link Asset} and a specified width, this method will return the best rendition
     * for that width (smallest rendition larger than the specified width) or the original.
     *
     * @param asset the asset for which to retrieve the best rendition
     * @param width the width
     * @return a rendition that is suitable for that width
     * @throws IOException when the best suited rendition is too large for processing
     */
    @NotNull
    private EnhancedRendition getBestRendition(@NotNull Asset asset, int width) throws IOException {
        // Sort renditions by file size
        SortedSet<Rendition> renditions = new TreeSet<>((o1, o2) -> Long.valueOf(o1.getSize() - o2.getSize()).intValue());
        renditions.addAll(asset.getRenditions());
        EnhancedRendition bestRendition = null;
        // Find first rendition that has a width larger or equal than wanted
        for (Rendition rendition : renditions) {
            EnhancedRendition enhancedRendition = new EnhancedRendition(rendition);
            Dimension dimension = enhancedRendition.getDimension();
            if (dimension != null) {
                if (dimension.getWidth() >= width) {
                    bestRendition = enhancedRendition;
                    if (StringUtils.equals(bestRendition.getPath(), asset.getOriginal().getPath())) {
                        metrics.markOriginalRenditionUsed();
                    }
                    break;
                }
            }
        }
        // If no rendition was found, attempt to use original
        if (bestRendition == null) {
            return getOriginal(asset);
        }
        return filter(bestRendition);
    }

    /**
     * Given an {@link Asset} it returns the original rendition, if it's not too large for processing.
     *
     * @param asset the asset for which to retrieve the original
     * @return the original asset
     * @throws IOException when the original is too large for processing
     */
    @NotNull
    private EnhancedRendition getOriginal(@NotNull Asset asset) throws IOException {
        EnhancedRendition original = new EnhancedRendition(asset.getOriginal());
        EnhancedRendition filtered = filter(original);
        metrics.markOriginalRenditionUsed();
        return filtered;
    }

    /**
     * Given a {@link EnhancedRendition} it will check its size to see if it's too large for processing.
     *
     * @param rendition the rendition that needs to be checked
     * @return the rendition if it's not too large
     * @throws IOException when the rendition is too large for processing
     */
    @NotNull
    private EnhancedRendition filter(@NotNull EnhancedRendition rendition) throws IOException {
        // Don't use too big renditions, to avoid running out of memory
        Dimension dimension = rendition.getDimension();
        if (dimension != null && dimension.getWidth() <= maxInputWidth) {
            return rendition;
        }
        metrics.markRejectedTooLargeRendition();
        throw new IOException(String.format("Cannot process rendition %s due to size %s", rendition.getName(), rendition.getDimension()));
    }

    private void stream(@NotNull SlingHttpServletResponse response, @NotNull InputStream inputStream, @NotNull String contentType,
                        String imageName)
            throws IOException {
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "inline; filename=" + URLEncoder.encode(imageName, CharEncoding.UTF_8));
        try {
            IOUtils.copy(inputStream, response.getOutputStream());
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * Retrieves the cropping rectangle, if one is defined for the image.
     *
     * @param properties the image component's properties
     * @return the cropping rectangle, if one is found, {@code null} otherwise
     */
    private Rectangle getCropRect(@NotNull ValueMap properties) {
        String csv = properties.get(ImageResource.PN_IMAGE_CROP, String.class);
        if (StringUtils.isNotEmpty(csv)) {
            try {
                int ratio = csv.indexOf('/');
                if (ratio >= 0) {
                    // skip ratio
                    csv = csv.substring(0, ratio);
                }
                String[] coords = csv.split(",");
                int x1 = Integer.parseInt(coords[0]);
                int y1 = Integer.parseInt(coords[1]);
                int x2 = Integer.parseInt(coords[2]);
                int y2 = Integer.parseInt(coords[3]);
                return new Rectangle(x1, y1, x2 - x1, y2 - y1);
            } catch (RuntimeException e) {
                LOGGER.warn(String.format("Invalid cropping rectangle %s.", csv), e);
            }
        }
        return null;
    }

    /**
     * Retrieves the rotation angle for the image, if one is present. Typically this should be a value between 0 and 360.
     *
     * @param properties the image component's properties
     * @return the rotation angle
     */
    private int getRotation(@NotNull ValueMap properties) {
        String rotationString = properties.get(ImageResource.PN_IMAGE_ROTATE, String.class);
        if (rotationString != null) {
            try {
                return Integer.parseInt(rotationString);
            } catch (NumberFormatException e) {
                LOGGER.warn(String.format("Invalid rotation value %s.", rotationString), e);
            }
        }
        return 0;
    }

    /**
     * Given a {@code String} value, this method will try to convert it to an {@code int}.
     *
     * @param stringValue the string value to convert
     * @return the {@code int} representation of the provided string, or 0 if the string cannot be parsed
     */
    private int getDimension(String stringValue) {
        try {
            return Integer.parseInt(stringValue);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Given an asset's width and height, together with a desired resize width, this method will calculate the resize height of the asset.
     *
     * @param assetWidth  the asset's width, in pixels
     * @param assetHeight the asset's height, in pixels
     * @param resizeWidth the resize width, in pixels
     * @return the resize height, in pixels; returns 0 if the resize width is greater than the asset's width
     */
    private int calculateResizeHeight(int assetWidth, int assetHeight, int resizeWidth) {
        if (assetWidth > 0 && assetHeight > 0 && resizeWidth < assetWidth) {
            // we only scale down, otherwise we return the original
            double scaleFactor = (double) resizeWidth / (double) assetWidth;
            return (int) (scaleFactor * assetHeight);
        }
        if (assetWidth > 0 && assetHeight > 0 && resizeWidth == assetWidth) {
            return assetHeight;
        }
        return 0;
    }

    /**
     * <p>
     * Checks if the {@code request} contains the {@code If-Modified-Since} header and compares this value to the passed {@code
     * lastModified} parameter.
     * </p>
     * <p/>
     * <p>If the value of {@code lastModified} is greater than 0 but less than or equal to the value of the {@code
     * If-Modified-Since} header, then {@link HttpServletResponse#SC_NOT_MODIFIED} will be set as the {@code response} status code.</p>
     * <p/>
     * <p>If the value of {@code lastModified} is greater than the value of the {@code If-Modified-Since} header, then this method will
     * set the {@link HttpConstants#HEADER_LAST_MODIFIED} {@code response} header with the value of {@code lastModified}.</p>
     * <p/>
     * <p>If the value of {@code lastModified} is less than or equal to 0 this method doesn't have any effect on the {@code response}.</p>
     *
     * @param request      the request
     * @param response     the response
     * @param lastModified the underlying resource's last modified date in milliseconds, expressed as UTC milliseconds from the Unix epoch
     *                     (00:00:00 UTC Thursday 1, January 1970)
     * @return {@code true} if the {@code response}'s status code was set (to {@link HttpServletResponse#SC_NOT_MODIFIED}, {@code false}
     * otherwise
     */
    private boolean handleIfModifiedSinceHeader(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response,
                                                long lastModified) {
        if (lastModified > 0) {
            long ifModifiedSince = request.getDateHeader(HttpConstants.HEADER_IF_MODIFIED_SINCE) / 1000;
            if (lastModified / 1000 <= ifModifiedSince) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                LOGGER.debug("If-Modified-Since header was present in the request. The resource was not changed, therefore replying with " +
                        "a 304 status code.");
                return true;
            }
            response.setDateHeader(HttpConstants.HEADER_LAST_MODIFIED, lastModified);
        }
        return false;
    }

    private String getImageType(String ext) {
        if (ext == null) {
            return DEFAULT_MIME;
        }
        if ("tiff".equalsIgnoreCase(ext) || "tif".equalsIgnoreCase(ext)) {
            return DEFAULT_MIME;
        }
        return mimeTypeService.getMimeType(ext);
    }

    /**
     * Returns the content policy bound to the given component.
     *
     * @param imageResource the resource identifying the accessed image component
     * @return the content policy. May be {@code nulll} in case no content policy can be found.
     */
    private ContentPolicy getContentPolicy(@NotNull Resource imageResource) {
        ResourceResolver resourceResolver = imageResource.getResourceResolver();
        ContentPolicyManager policyManager = resourceResolver.adaptTo(ContentPolicyManager.class);
        if (policyManager != null) {
            ComponentManager componentManager = resourceResolver.adaptTo(ComponentManager.class);
            if (componentManager != null) {
                com.day.cq.wcm.api.components.Component component = componentManager.getComponentOfResource(imageResource);
                if (component != null && component.getProperties() != null) {
                    String delegatingResourceType =
                            component.getProperties().get(AbstractImageDelegatingModel.IMAGE_DELEGATE, String.class);
                    if (StringUtils.isNotEmpty(delegatingResourceType)) {
                        imageResource = new CoreResourceWrapper(imageResource, delegatingResourceType);
                    }
                }
            }
            return policyManager.getPolicy(imageResource);
        } else {
            LOGGER.warn("Could not get policy manager from resource resolver!");
        }
        return null;
    }

    /**
     * Creates a {@link List} from the given selector string. A valid selector can be:
     *      * handler or
     *      * handler.width or
     *      * handler.quality.width
     *
     * @param selector string to create the List from
     * @return {@link List} of selector items
     * @throws IllegalArgumentException in case the selector is not valid
     */
    private List<String> selectorToList(String selector) throws IllegalArgumentException {
        if (StringUtils.isEmpty(selector)) {
            throw new IllegalArgumentException("Expected 1, 2 or 3 selectors instead got empty selector");
        }
        ArrayList<String> selectorList = Lists.newArrayList(Splitter.on('.').omitEmptyStrings().trimResults().split(selector));
        if (selectorList.size() > 3) {
            throw new IllegalArgumentException("Expected 1, 2 or 3 selectors, instead got: " + selectorList.size());
        }
        return selectorList;
    }

    /**
     * Creates an image transformation map from the given selector items.
     *
     * @param selectorList to get the parameter from
     * @return {@link Map} with quality and width transformation parameter
     */
    private Map<String, Integer> getTransformationMap(List<String> selectorList, Resource component) throws IllegalArgumentException {
        Map<String, Integer> selectorParameterMap = new HashMap<>();
        int width = this.defaultResizeWidth;
        if (selectorList.size() > 1) {
            String widthString = (selectorList.size() > 2 ? selectorList.get(2) : selectorList.get(1));
            try {
                width = Integer.parseInt(widthString);
                if (width <= 0) {
                    throw new IllegalArgumentException();
                }
                List<Integer> allowedRenditionWidths = getAllowedRenditionWidths(component);
                if (!allowedRenditionWidths.contains(width)) {
                    throw new IllegalArgumentException("The requested width is not allowed in the content policy or no default");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Third selector must contain a valid width information (selector > 0)");
            }
        }
        selectorParameterMap.put(SELECTOR_WIDTH_KEY, width);

        int quality = DEFAULT_JPEG_QUALITY;
        if (selectorList.size() > 2) {
            String qualityString = selectorList.get(1);
            try {
                int qualityPercentage = Integer.parseInt(qualityString);
                if (qualityPercentage <= 0 || qualityPercentage > 100) {
                    throw new IllegalArgumentException();
                }
                Integer allowedJpegQuality = getAllowedJpegQuality(component);
                if (qualityPercentage != allowedJpegQuality) {
                    throw new IllegalArgumentException("The requested quality is not allowed in the content policy or no default");
                }
                quality = qualityPercentage;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Second selector must be a valid quality in percentage (100 <= selector > 0)");
            }
        }
        selectorParameterMap.put(SELECTOR_QUALITY_KEY, quality);

        return selectorParameterMap;
    }

    /**
     * Returns the list of allowed renditions sizes from this component's content policy. If the component doesn't have a content policy,
     * then the list will only contain the default resize width. Rendition widths that are not valid {@link Integer} numbers will be
     * ignored.
     *
     * @param imageResource the resource identifying the accessed image component
     * @return the list of the allowed widths; the list will be <i>empty</i> if the component doesn't have a content policy
     */
    private List<Integer> getAllowedRenditionWidths(@NotNull Resource imageResource) {
        List<Integer> list = new ArrayList<>();
        ContentPolicy contentPolicy = getContentPolicy(imageResource);
        if (contentPolicy != null) {
            String[] allowedRenditionWidths = contentPolicy.getProperties()
                    .get(com.adobe.cq.wcm.core.components.models.Image.PN_DESIGN_ALLOWED_RENDITION_WIDTHS, new String[0]);
            for (String width : allowedRenditionWidths) {
                try {
                    list.add(Integer.parseInt(width));
                } catch (NumberFormatException e) {
                    LOGGER.warn("One of the configured widths ({}) from the {} content policy is not a valid Integer.", width,
                            contentPolicy.getPath());
                    return list;
                }
            }
        }
        if (list.isEmpty()) {
            list.add(this.defaultResizeWidth);
        }
        return list;
    }

    /**
     * Returns the allowed JPEG quality from this component's content policy.
     *
     * @param imageResource the resource identifying the accessed image component
     * @return the JPEG quality in the range 0..100 or {@link #DEFAULT_JPEG_QUALITY} if the component doesn't have a content policy or doesn't have this policy property set to an Integer.
     */
    private Integer getAllowedJpegQuality(@NotNull Resource imageResource) {
        Integer allowedJpegQuality = DEFAULT_JPEG_QUALITY;
        ContentPolicy contentPolicy = getContentPolicy(imageResource);
        if (contentPolicy != null) {
            allowedJpegQuality = contentPolicy.getProperties()
                    .get(com.adobe.cq.wcm.core.components.models.Image.PN_DESIGN_JPEG_QUALITY, DEFAULT_JPEG_QUALITY);
        }
        return allowedJpegQuality;
    }

    private long getRequestLastModifiedSuffix(@Nullable String suffix) {
        long requestLastModified = 0;
        if (StringUtils.isNotEmpty(suffix) && suffix.contains(".")) {
            // check if the 13 digits UTC milliseconds timestamp, preceded by a forward slash is present in the suffix
            Pattern p = Pattern.compile("\\(|\\)|\\/\\d{13}");
            Matcher m = p.matcher(suffix);
            if (!m.find()) {
                return requestLastModified;
            }
            try {
                requestLastModified = Long.parseLong(ResourceUtil.getName(m.group()));
            } catch (NumberFormatException e) {
                // do nothing
            }
        }
        return requestLastModified;
    }

    private enum Source {
        ASSET,
        FILE,
        NOCONTENT,
        NONEXISTING
    }

    private static class ImageComponent {
        Source source = Source.NONEXISTING;
        Resource imageResource;

        ImageComponent(@NotNull Resource component) {
            String fileReference = component.getValueMap().get(DownloadResource.PN_REFERENCE, String.class);
            Resource childFileNode = component.getChild(DownloadResource.NN_FILE);
            if (StringUtils.isEmpty(fileReference) && childFileNode == null) {
                source = Source.NOCONTENT;
            } else if (StringUtils.isNotEmpty(fileReference)) {
                imageResource = component.getResourceResolver().getResource(fileReference);
                if (imageResource != null) {
                    source = Source.ASSET;
                }
            } else {
                if (childFileNode != null) {
                    if (JcrConstants.NT_FILE.equals(childFileNode.getResourceType())) {
                        Resource jcrContent = childFileNode.getChild(JcrConstants.JCR_CONTENT);
                        if (jcrContent != null) {
                            if (jcrContent.getValueMap().containsKey(JcrConstants.JCR_DATA)) {
                                imageResource = childFileNode;
                                source = Source.FILE;
                            }
                        }
                    }
                }
            }
        }
    }
}
