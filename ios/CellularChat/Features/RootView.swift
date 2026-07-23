import SwiftUI
import HalfmoonTokens

struct RootView: View {
    @EnvironmentObject private var appModel: AppModel

    var body: some View {
        NavigationStack {
            PeopleView()
        }
        .tint(HM.color.actionPrimaryBg)
        .foregroundStyle(HM.color.fgDefault)
        .background(HM.color.bgDefault)
    }
}
