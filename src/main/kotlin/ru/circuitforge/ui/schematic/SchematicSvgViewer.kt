package ru.circuitforge.ui.schematic

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas
import javafx.scene.control.ScrollPane
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import org.slf4j.LoggerFactory

/**
 * Просмотрщик SVG-схем с зумом и панорамированием.
 * Использует JSVG для рендеринга SVG.
 */
class SchematicSvgViewer : Pane() {
    private val logger = LoggerFactory.getLogger(SchematicSvgViewer::class.java)
    private val contentPane = Pane()
    private val scrollPane =
        ScrollPane(contentPane).apply {
            hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            fitToWidthProperty().value = true
            fitToHeightProperty().value = true
        }

    private var currentSvg: String? = null
    private var scale = 1.0
    private val svgLoader = SVGLoader()

    init {
        children.add(scrollPane)
        setupZoomAndPan()
    }

    /**
     * Загружает SVG-контент для отображения.
     */
    fun loadSvg(svgContent: String) {
        currentSvg = svgContent
        try {
            // Удаляем старый контент
            contentPane.children.clear()

            // Загружаем SVGDocument через JSVG
            val inputStream = java.io.ByteArrayInputStream(svgContent.toByteArray(Charsets.UTF_8))
            val svgDocument: SVGDocument? = svgLoader.load(inputStream, null, LoaderContext.createDefault())

            if (svgDocument != null) {
                // Создаём FXSVGCanvas для рендеринга
                val canvas =
                    FXSVGCanvas().apply {
                        renderBackend = FXSVGCanvas.RenderBackend.JavaFX
                        this.document = svgDocument
                    }

                // Устанавливаем размеры из viewBox
                val viewBox = svgDocument.viewBox()
                val width = viewBox.width.toDouble()
                val height = viewBox.height.toDouble()

                canvas.prefWidth = width
                canvas.prefHeight = height
                canvas.minWidth = width
                canvas.minHeight = height
                canvas.maxWidth = width
                canvas.maxHeight = height

                contentPane.children.add(canvas)
                contentPane.prefWidth = width
                contentPane.prefHeight = height
            }

            scale = 1.0
        } catch (e: Exception) {
            logger.error("Failed to load SVG: ${e.message}", e)
        }
    }

    /**
     * Загружает SVG из файла.
     */
    fun loadSvgFile(filePath: String) {
        loadSvg(java.io.File(filePath).readText())
    }

    private fun setupZoomAndPan() {
        // Zoom: scroll wheel
        setOnScroll { event ->
            if (event.deltaY > 0) {
                scale *= 1.1
            } else {
                scale /= 1.1
            }
            updateScale()
            event.consume()
        }

        // Pan: mouse drag
        var isPanning = false
        var lastX = 0.0
        var lastY = 0.0

        setOnMousePressed { event ->
            if (event.button == MouseButton.PRIMARY) {
                isPanning = true
                lastX = event.sceneX
                lastY = event.sceneY
            }
        }

        setOnMouseDragged { event ->
            if (isPanning) {
                val deltaX = event.sceneX - lastX
                val deltaY = event.sceneY - lastY
                scrollPane.hvalue -= deltaX / 100.0
                scrollPane.vvalue -= deltaY / 100.0
                lastX = event.sceneX
                lastY = event.sceneY
            }
        }

        setOnMouseReleased { event ->
            if (event.button == MouseButton.PRIMARY) {
                isPanning = false
            }
        }
    }

    private fun updateScale() {
        contentPane.scaleX = scale
        contentPane.scaleY = scale
    }
}
