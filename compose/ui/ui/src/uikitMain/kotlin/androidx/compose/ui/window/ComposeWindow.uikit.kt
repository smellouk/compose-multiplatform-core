/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.createSkiaLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toSkiaRect
import androidx.compose.ui.interop.LocalLayerContainer
import androidx.compose.ui.interop.SkikoBackendTextureToImage
import androidx.compose.ui.interop.SkikoTouchEventHandler
import androidx.compose.ui.native.ComposeLayer
import androidx.compose.ui.platform.Platform
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.UIKitTextInputService
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.toOffset
import kotlin.math.roundToInt
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import org.jetbrains.skia.GrBackendTexture
import org.jetbrains.skia.Point
import org.jetbrains.skiko.SkikoUIView
import org.jetbrains.skiko.TextActions
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSCoder
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSValue
import platform.UIKit.CGRectValue
import platform.UIKit.UIEvent
import platform.UIKit.UIScreen
import platform.UIKit.UITouch
import platform.UIKit.UITouchPhase
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIViewControllerTransitionCoordinatorProtocol
import platform.UIKit.addSubview
import platform.UIKit.reloadInputViews
import platform.UIKit.setClipsToBounds
import platform.UIKit.setNeedsDisplay
import platform.UIKit.window
import platform.darwin.NSObject

// The only difference with macos' Window is that
// it has return type of UIViewController rather than unit.
fun Application(
    title: String = "JetpackNativeWindow",
    content: @Composable () -> Unit = { }

) = ComposeWindow().apply {
    setTitle(title)
    setContent(content)
} as UIViewController

@ExportObjCClass
internal actual class ComposeWindow : UIViewController {
    @OverrideInit
    actual constructor() : super(nibName = null, bundle = null)

    @OverrideInit
    constructor(coder: NSCoder) : super(coder)

    private val density: Density
        get() = Density(layer.layer.contentScale)

    private lateinit var layer: ComposeLayer
    private lateinit var content: @Composable () -> Unit
    private val keyboardVisibilityListener = object : NSObject() {
        @Suppress("unused")
        @ObjCAction
        fun keyboardWillShow(arg: NSNotification) {
            val keyboardInfo = arg.userInfo!!["UIKeyboardFrameEndUserInfoKey"] as NSValue
            val keyboardHeight = keyboardInfo.CGRectValue().useContents { size.height }
            val screenHeight = UIScreen.mainScreen.bounds.useContents { size.height }
            val focused = layer.getActiveFocusRect()
            if (focused != null) {
                val focusedBottom = focused.bottom.value + getTopLeftOffset().y
                val hiddenPartOfFocusedElement = focusedBottom + keyboardHeight - screenHeight
                if (hiddenPartOfFocusedElement > 0) {
                    // If focused element hidden by keyboard, then change UIView bounds.
                    // Focused element will be visible
                    view.setClipsToBounds(true)
                    val (width, height) = getViewFrameSize()
                    view.layer.setBounds(
                        CGRectMake(
                            x = 0.0,
                            y = hiddenPartOfFocusedElement,
                            width = width.toDouble(),
                            height = height.toDouble()
                        )
                    )
                }
            }
        }

        @Suppress("unused")
        @ObjCAction
        fun keyboardWillHide(arg: NSNotification) {
            val (width, height) = getViewFrameSize()
            view.layer.setBounds(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        }

        @Suppress("unused")
        @ObjCAction
        fun keyboardDidHide(arg: NSNotification) {
            view.setClipsToBounds(false)
        }
    }

    actual fun setTitle(title: String) {
        println("TODO: set title to SkiaWindow")
    }

    override fun loadView() {
        val skiaLayer = createSkiaLayer()
        val skikoUIView = SkikoUIView(
            skiaLayer = skiaLayer,
            hitTest = { point: Point, withEvent: UIEvent? ->
                val isInteropView = layer.scene.mainOwner?.hitInterop(Offset(point.x * density.density, point.y * density.density), true) ?: false
                !isInteropView
            },
        ).load()
        val rootView = UIView()
        rootView.addSubview(skikoUIView)
        view = rootView
        val uiKitTextInputService = UIKitTextInputService(
            showSoftwareKeyboard = {
                skikoUIView.showScreenKeyboard()
            },
            hideSoftwareKeyboard = {
                skikoUIView.hideScreenKeyboard()
            },
            updateView = {
                skikoUIView.setNeedsDisplay() // redraw on next frame
                platform.QuartzCore.CATransaction.flush() // clear all animations
                skikoUIView.reloadInputViews() // update input (like screen keyboard)
            },
            textWillChange = { skikoUIView.textWillChange() },
            textDidChange = { skikoUIView.textDidChange() },
            selectionWillChange = { skikoUIView.selectionWillChange() },
            selectionDidChange = { skikoUIView.selectionDidChange() },
        )
        val uiKitPlatform = object : Platform by Platform.Empty {
            override val textInputService: PlatformTextInputService = uiKitTextInputService
            override val viewConfiguration =
                object : ViewConfiguration {
                    override val longPressTimeoutMillis: Long get() = 500
                    override val doubleTapTimeoutMillis: Long get() = 300
                    override val doubleTapMinTimeMillis: Long get() = 40
                    override val touchSlop: Float get() = with(density) { 3.dp.toPx() }
                }
            override val textToolbar = object : TextToolbar {
                override fun showMenu(
                    rect: Rect,
                    onCopyRequested: (() -> Unit)?,
                    onPasteRequested: (() -> Unit)?,
                    onCutRequested: (() -> Unit)?,
                    onSelectAllRequested: (() -> Unit)?
                ) {
                    val skiaRect = with(density) {
                        org.jetbrains.skia.Rect.makeLTRB(
                            l = rect.left / density,
                            t = rect.top / density,
                            r = rect.right / density,
                            b = rect.bottom / density,
                        )
                    }
                    skikoUIView.showTextMenu(
                        targetRect = skiaRect,
                        textActions = object : TextActions {
                            override val copy: (() -> Unit)? = onCopyRequested
                            override val cut: (() -> Unit)? = onCutRequested
                            override val paste: (() -> Unit)? = onPasteRequested
                            override val selectAll: (() -> Unit)? = onSelectAllRequested
                        }
                    )
                }

                /**
                 * TODO on UIKit native behaviour is hide text menu, when touch outside
                 */
                override fun hide() = skikoUIView.hideTextMenu()

                override val status: TextToolbarStatus
                    get() = if (skikoUIView.isTextMenuShown())
                        TextToolbarStatus.Shown
                    else
                        TextToolbarStatus.Hidden
            }
        }
        layer = ComposeLayer(
            layer = skiaLayer,
            platform = uiKitPlatform,
            getTopLeftOffset = ::getTopLeftOffset,
            input = uiKitTextInputService.skikoInput,
        )
        layer.setContent(content = {
            CompositionLocalProvider(
                LocalLayerContainer provides rootView,
                SkikoTouchEventHandler provides {
                    skiaLayer.skikoView?.onTouchEvent(it)
                },
                SkikoBackendTextureToImage provides { texture: GrBackendTexture ->
                    skiaLayer.backendTextureToImage(texture)
                },
            ) {
                content()
            }
        })
    }

    override fun viewWillTransitionToSize(
        size: CValue<CGSize>,
        withTransitionCoordinator: UIViewControllerTransitionCoordinatorProtocol
    ) {
        layer.setDensity(density)
        val scale = density.density
        val width = size.useContents { width } * scale
        val height = size.useContents { height } * scale
        layer.setSize(width.roundToInt(), height.roundToInt())
        super.viewWillTransitionToSize(size, withTransitionCoordinator)
    }

    override fun viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        val (width, height) = getViewFrameSize()
        layer.setDensity(density)
        val scale = density.density
        layer.setSize((width * scale).roundToInt(), (height * scale).roundToInt())
    }//todo check density on widgets gallery

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        NSNotificationCenter.defaultCenter.addObserver(
            observer = keyboardVisibilityListener,
            selector = NSSelectorFromString("keyboardWillShow:"),
            name = platform.UIKit.UIKeyboardWillShowNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.addObserver(
            observer = keyboardVisibilityListener,
            selector = NSSelectorFromString("keyboardWillHide:"),
            name = platform.UIKit.UIKeyboardWillHideNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.addObserver(
            observer = keyboardVisibilityListener,
            selector = NSSelectorFromString("keyboardDidHide:"),
            name = platform.UIKit.UIKeyboardDidHideNotification,
            `object` = null
        )
    }

    // viewDidUnload() is deprecated and not called.
    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        this.dispose()
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = platform.UIKit.UIKeyboardWillShowNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = platform.UIKit.UIKeyboardWillHideNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = platform.UIKit.UIKeyboardDidHideNotification,
            `object` = null
        )
    }

    override fun didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        error("didReceiveMemoryWarning, maybe memory leak")
    }

    actual fun setContent(
        content: @Composable () -> Unit
    ) {
        println("ComposeWindow.setContent")
        this.content = content
    }

    actual fun dispose() {
        layer.dispose()
    }

    private fun getViewFrameSize(): IntSize {
        val (width, height) = view.frame().useContents { this.size.width to this.size.height }
        return IntSize(width.toInt(), height.toInt())
    }

    private fun getTopLeftOffset(): Offset {
        val topLeftPoint =
            view.coordinateSpace().convertPoint(
                point = CGPointMake(0.0, 0.0),
                toCoordinateSpace = UIScreen.mainScreen.coordinateSpace()
            )
        return topLeftPoint.useContents { DpOffset(x.dp, y.dp).toOffset(density) }
    }

}