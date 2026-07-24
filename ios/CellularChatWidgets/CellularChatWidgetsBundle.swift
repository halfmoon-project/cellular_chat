import WidgetKit
import SwiftUI

/// Widget extension entry point. Hosts the Find Live Activity (IMPLEMENTATION_PLAN
/// §5 background controller).
@main
struct CellularChatWidgetsBundle: WidgetBundle {
    var body: some Widget {
        FindLiveActivityWidget()
    }
}
