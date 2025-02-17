/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Rect
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.fresco.ui.common.VitoUtils
import com.facebook.fresco.urimod.Dimensions
import com.facebook.fresco.urimod.UriModifier
import com.facebook.fresco.urimod.UriModifierInterface
import com.facebook.fresco.vito.core.ImagePipelineUtils
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptions.Companion.defaults
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.fresco.vito.source.IncreasingQualityImageSource
import com.facebook.fresco.vito.source.UriImageSource
import com.facebook.imagepipeline.core.ImagePipeline
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.listener.RequestListener
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection

/** Vito image pipeline to fetch an image for a given VitoImageRequest. */
class VitoImagePipelineImpl(
    private val imagePipeline: ImagePipeline,
    private val imagePipelineUtils: ImagePipelineUtils
) : VitoImagePipeline {

  override fun createImageRequest(
      resources: Resources,
      imageSource: ImageSource,
      options: ImageOptions?,
      logWithHighSamplingRate: Boolean,
      viewport: Rect?,
      callerContext: Any?,
      contextChain: ContextChain?,
      forceKeepOriginalSize: Boolean,
  ): VitoImageRequest {
    val imageOptions = options ?: defaults()
    val extras: MutableMap<String, Any> = mutableMapOf()
    var finalImageSource = imageSource
    val imageSourceClass = imageSource.javaClass.simpleName

    val modifiedUriValue: String
    if (imageOptions.experimentalDynamicSize && !forceKeepOriginalSize) {
      if (imageSource is UriImageSource) {
        val result: UriModifierInterface.ModificationResult =
            UriModifier.INSTANCE.modifyUri(
                imageSource.imageUri,
                viewport?.let { Dimensions(it.width(), it.height()) },
                imageOptions.actualImageScaleType,
                callerContext,
                contextChain)
        modifiedUriValue = result.toString()
        if (result is UriModifierInterface.ModificationResult.Modified) {
          finalImageSource = ImageSourceProvider.forUri(result.newUri)
        }
      } else {
        modifiedUriValue = "NotSupportedImageSource: $imageSourceClass"
      }
    } else if (imageOptions.experimentalDynamicSize &&
        imageOptions.experimentalDynamicSizeWithCacheFallback &&
        forceKeepOriginalSize) {
      modifiedUriValue = "MBPDiskFallbackEnabled"
    } else {
      modifiedUriValue = "Disabled"
    }

    extras[HasExtraData.KEY_MODIFIED_URL] = modifiedUriValue
    extras[HasExtraData.KEY_IMAGE_SOURCE_TYPE] = imageSourceClass

    if (imageSource is IncreasingQualityImageSource) {
      imageSource.extras?.let { extras[HasExtraData.KEY_IMAGE_SOURCE_EXTRAS] = it }
    } else if (imageSource is UriImageSource) {
      imageSource.extras?.let { extras[HasExtraData.KEY_IMAGE_SOURCE_EXTRAS] = it }
    }

    val finalImageRequest =
        ImageSourceToImagePipelineAdapter.maybeExtractFinalImageRequest(
            finalImageSource, imagePipelineUtils, imageOptions)
    val finalImageCacheKey = finalImageRequest?.let { imagePipeline.getCacheKey(it, null) }

    return VitoImageRequest(
        resources,
        finalImageSource,
        imageOptions,
        logWithHighSamplingRate,
        finalImageRequest,
        finalImageCacheKey,
        extras)
  }

  override fun getCachedImage(imageRequest: VitoImageRequest): CloseableReference<CloseableImage>? =
      traceSection("VitoImagePipeline#getCachedImage") {
        val cachedImageReference = imagePipeline.getCachedImage(imageRequest.finalImageCacheKey)
        if (CloseableReference.isValid(cachedImageReference)) {
          cachedImageReference
        } else {
          null
        }
      }

  override fun fetchDecodedImage(
      imageRequest: VitoImageRequest,
      callerContext: Any?,
      requestListener: RequestListener?,
      uiComponentId: Long
  ): DataSource<CloseableReference<CloseableImage>> =
      ImageSourceToImagePipelineAdapter.createDataSourceSupplier(
              imageRequest.imageSource,
              imagePipeline,
              imagePipelineUtils,
              imageRequest.imageOptions,
              callerContext,
              requestListener,
              VitoUtils.getStringId(uiComponentId),
              imageRequest.extras)
          .get()

  override fun isInDiskCacheSync(
      vitoImageRequest: VitoImageRequest,
  ): Boolean {
    val imageRequest = vitoImageRequest.finalImageRequest ?: return false
    return imagePipeline.isInDiskCacheSync(imageRequest)
  }
}
