import SwiftUI
import UniformTypeIdentifiers

/// The folders songs come from: the app's own Documents folder (and its
/// subfolders) plus folders linked from the Files app, which are read in place.
/// Folders can be hidden (their songs leave every tab) and linked folders unlinked.
struct FoldersView: View {
    @EnvironmentObject private var library: LibraryManager
    @EnvironmentObject private var settings: SettingsStore
    @State private var showPicker = false
    @State private var linkMessage: String?
    @State private var unlinkTarget: LinkedFolder?

    var body: some View {
        let groups = library.folderGroups()
        let visible = groups.filter { !$0.isHidden }
        let hidden = groups.filter(\.isHidden)

        NavigationStack {
            List {
                Section {
                    ForEach(visible) { group in
                        folderRow(group)
                            .swipeActions {
                                Button("Hide", systemImage: "eye.slash") { library.hideFolder(group.key) }
                                    .tint(.orange)
                            }
                            .contextMenu {
                                Button("Hide Folder", systemImage: "eye.slash") { library.hideFolder(group.key) }
                            }
                    }
                } header: {
                    Text("Folders with Music")
                } footer: {
                    Text("Swipe left on a folder (or long-press it) to hide its songs from every tab.")
                }

                if !hidden.isEmpty {
                    Section("Hidden") {
                        ForEach(hidden) { group in
                            HStack {
                                folderLabel(group).opacity(0.5)
                                Spacer()
                                Button("Show") { library.showFolder(group.key) }
                                    .buttonStyle(.bordered)
                            }
                        }
                    }
                }

                Section {
                    LabeledContent("On This iPhone", value: "Always included")
                    ForEach(settings.settings.linkedFolders) { folder in
                        HStack {
                            Label(folder.name, systemImage: "link")
                            Spacer()
                            Button("Unlink", role: .destructive) { unlinkTarget = folder }
                                .buttonStyle(.bordered)
                        }
                        .swipeActions {
                            Button("Unlink", systemImage: "xmark", role: .destructive) {
                                Task { await library.unlinkFolder(folder.id) }
                            }
                        }
                    }
                    Button {
                        showPicker = true
                    } label: {
                        Label("Link a Folder…", systemImage: "folder.badge.plus")
                    }
                } header: {
                    Text("Sources")
                } footer: {
                    Text("Linked folders (iCloud Drive, On My iPhone, USB drives…) are played where they are, "
                         + "subfolders included; nothing is copied and unlinking deletes nothing. Songs you "
                         + "import with + and files added through Finder go into On This iPhone.")
                }
            }
            .overlay {
                if groups.isEmpty && !library.isScanning {
                    ContentUnavailableView("No Folders", systemImage: "folder",
                                           description: Text("Import songs or link a folder."))
                        .allowsHitTesting(false)
                        .padding(.bottom, 200)
                }
            }
            .navigationTitle("Folders")
            .navigationDestination(for: FolderGroup.self) { group in
                SongCollectionView(title: group.name, sortByAlbum: false) { $0.folderKey == group.key }
            }
            .refreshable { await library.reload() }
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.folder]) { result in
                guard case .success(let url) = result else { return }
                Task {
                    switch await library.linkFolder(url) {
                    case .linked: break
                    case .alreadyIncluded:
                        linkMessage = "That folder is already in your library (it's linked already, "
                            + "or inside a folder that is)."
                    case .noAccess:
                        linkMessage = "The app wasn't given access to that folder."
                    }
                }
            }
            .alert("Couldn't Link Folder", isPresented: Binding(get: { linkMessage != nil },
                                                                 set: { if !$0 { linkMessage = nil } })) {
                Button("OK", role: .cancel) { linkMessage = nil }
            } message: {
                Text(linkMessage ?? "")
            }
            .confirmationDialog("Unlink \(unlinkTarget?.name ?? "folder")?",
                                isPresented: Binding(get: { unlinkTarget != nil },
                                                     set: { if !$0 { unlinkTarget = nil } }),
                                titleVisibility: .visible) {
                Button("Unlink", role: .destructive) {
                    if let id = unlinkTarget?.id { Task { await library.unlinkFolder(id) } }
                    unlinkTarget = nil
                }
            } message: {
                Text("Its songs leave the library. The files themselves aren't deleted.")
            }
        }
    }

    private func folderRow(_ group: FolderGroup) -> some View {
        NavigationLink(value: group) { folderLabel(group) }
    }

    private func folderLabel(_ group: FolderGroup) -> some View {
        HStack(spacing: 12) {
            Image(systemName: group.isHidden ? "folder" : "folder.fill")
                .font(.title3)
                .foregroundStyle(Color.accentColor)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(group.name).lineLimit(1)
                Text(group.detail.isEmpty
                     ? songCount(group.songCount)
                     : "\(group.detail) · \(songCount(group.songCount))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
        }
    }

    private func songCount(_ n: Int) -> String {
        "\(n) song\(n == 1 ? "" : "s")"
    }
}
