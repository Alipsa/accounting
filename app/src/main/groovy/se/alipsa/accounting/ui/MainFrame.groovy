package se.alipsa.accounting.ui

import groovy.swing.SwingBuilder
import groovy.transform.CompileDynamic

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Image
import java.util.logging.Logger

import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Main desktop window with top-level navigation placeholders for phase one.
 */
@CompileDynamic
final class MainFrame {

    private static final Logger log = Logger.getLogger(MainFrame.name)
    private static final List<Map<String, String>> TAB_DEFINITIONS = [
        [title: 'Översikt', description: 'Översikt och status för bokföringen kommer här.'],
        [title: 'Kontoplan', description: 'Kontoplanen visas här när kontomodulen är implementerad.'],
        [title: 'Verifikationer', description: 'Registrering och sökning av verifikationer kommer här.'],
        [title: 'Rapporter', description: 'Rapporter och export kommer här.'],
        [title: 'Inställningar', description: 'Företagsinställningar och systeminställningar kommer här.']
    ]
    private static final List<String> ICON_PATHS = [
        '/icons/logo16.png',
        '/icons/logo32.png',
        '/icons/logo64.png',
        '/icons/logo128.png'
    ]

    private final SwingBuilder swing = new SwingBuilder()
    private JLabel statusLabel
    private final JFrame frame

    MainFrame() {
        frame = buildFrame()
        applyIcons()
        setStatus('Applikationen är startad och redo för fortsatt implementation.')
    }

    void show() {
        frame.visible = true
    }

    void setStatus(String text) {
        statusLabel.text = text
    }

    private JFrame buildFrame() {
        swing.frame(
            title: 'Alipsa Accounting',
            size: [1100, 720],
            defaultCloseOperation: JFrame.EXIT_ON_CLOSE,
            locationByPlatform: true,
            show: false
        ) {
            lookAndFeel 'system'
            menuBar {
                menu(text: 'Arkiv') {
                    menuItem(text: 'Avsluta', actionPerformed: { exitRequested() })
                }
                menu(text: 'Hjälp') {
                    menuItem(text: 'Om', actionPerformed: { showAboutDialog() })
                }
            }
            borderLayout()
            panel(constraints: BorderLayout.NORTH, border: swing.emptyBorder(12, 16, 8, 16)) {
                borderLayout()
                label(
                    text: 'Alipsa Accounting',
                    horizontalAlignment: SwingConstants.LEFT,
                    font: new java.awt.Font('Dialog', java.awt.Font.BOLD, 22),
                    constraints: BorderLayout.CENTER
                )
            }
            tabbedPane(constraints: BorderLayout.CENTER) {
                TAB_DEFINITIONS.each { Map<String, String> tab ->
                    widget(buildPlaceholderPanel(tab.title, tab.description), title: tab.title)
                }
            }
            panel(constraints: BorderLayout.SOUTH, border: swing.lineBorder(color: Color.LIGHT_GRAY)) {
                borderLayout()
                statusLabel = label(
                    text: 'Redo',
                    border: swing.emptyBorder(6, 12, 6, 12),
                    constraints: BorderLayout.CENTER
                ) as JLabel
            }
        } as JFrame
    }

    private JPanel buildPlaceholderPanel(String title, String description) {
        swing.panel(border: swing.emptyBorder(24, 24, 24, 24)) {
            borderLayout()
            label(
                text: "<html><h2>${title}</h2><p>${description}</p></html>",
                horizontalAlignment: SwingConstants.CENTER,
                constraints: BorderLayout.CENTER
            )
        } as JPanel
    }

    private void exitRequested() {
        frame.dispose()
    }

    private void showAboutDialog() {
        ImageIcon icon = loadIcon('/icons/logo64.png')
        JOptionPane.showMessageDialog(
            frame,
            'Alipsa Accounting\nFas 1: grundplattform och databasbootstrap.',
            'Om Alipsa Accounting',
            JOptionPane.INFORMATION_MESSAGE,
            icon
        )
    }

    private void applyIcons() {
        List<Image> icons = []
        ICON_PATHS.each { String path ->
            ImageIcon icon = loadIcon(path)
            if (icon != null) {
                icons << icon.image
            }
        }
        if (icons) {
            frame.iconImages = icons
        } else {
            log.fine('No application icons were available on the classpath.')
        }
    }

    private ImageIcon loadIcon(String path) {
        InputStream stream = MainFrame.getResourceAsStream(path)
        if (stream == null) {
            return null
        }
        stream.withCloseable { InputStream input ->
            new ImageIcon(ImageIO.read(input))
        }
    }
}
