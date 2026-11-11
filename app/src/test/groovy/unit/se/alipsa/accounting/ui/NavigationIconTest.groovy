package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.*

import com.formdev.flatlaf.extras.FlatSVGIcon
import org.junit.jupiter.api.Test

final class NavigationIconTest {

  @Test
  void allNavigationIconsAreFoundOnClasspath() {
    MainFrame.NAV_ICON_FILES.each { String fileName ->
      FlatSVGIcon icon = MainFrame.navIcon(fileName) as FlatSVGIcon
      assertTrue(icon.hasFound(), "Navigation icon ${fileName} must exist on the classpath".toString())
    }
  }
}
