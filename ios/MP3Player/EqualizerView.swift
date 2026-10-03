import SwiftUI

/// The desktop's 10-band EQ with presets and post-gain, applied in the audio engine.
struct EqualizerView: View {
    @EnvironmentObject private var settings: SettingsStore

    private var eq: AppSettings { settings.settings }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Equalizer", isOn: Binding(
                        get: { eq.eqEnabled },
                        set: { on in settings.update { $0.eqEnabled = on } }))
                    Menu {
                        ForEach(EqualizerConfig.presets, id: \.name) { preset in
                            Button(preset.name) {
                                settings.update { $0.eqGainsDb = preset.gains }
                            }
                        }
                    } label: {
                        LabeledContent("Preset", value: presetName)
                    }
                }

                Section("Bands") {
                    ForEach(0..<EqualizerConfig.bandCount, id: \.self) { band in
                        HStack {
                            Text(EqualizerConfig.label(for: EqualizerConfig.frequencies[band]) + " Hz")
                                .font(.caption.monospacedDigit())
                                .frame(width: 58, alignment: .leading)
                            Slider(value: gainBinding(band),
                                   in: EqualizerConfig.minGainDb...EqualizerConfig.maxGainDb, step: 0.5)
                            Text(dbText(eq.eqGainsDb[band]))
                                .font(.caption.monospacedDigit())
                                .frame(width: 52, alignment: .trailing)
                        }
                    }
                }
                .disabled(!eq.eqEnabled)

                Section {
                    HStack {
                        Text("Post-gain")
                        Slider(value: Binding(
                            get: { eq.eqPostGainDb },
                            set: { value in settings.update { $0.eqPostGainDb = value } }),
                               in: EqualizerConfig.postGainRange, step: 0.5)
                        Text(dbText(eq.eqPostGainDb))
                            .font(.caption.monospacedDigit())
                            .frame(width: 52, alignment: .trailing)
                    }
                    Button("Reset to Flat") {
                        settings.update {
                            $0.eqGainsDb = Array(repeating: 0, count: EqualizerConfig.bandCount)
                            $0.eqPostGainDb = 0
                        }
                    }
                } footer: {
                    Text("Lower the post-gain if boosted bands make loud songs distort.")
                }
                .disabled(!eq.eqEnabled)
            }
            .navigationTitle("Equalizer")
        }
    }

    private var presetName: String {
        EqualizerConfig.presets.first { $0.gains == eq.eqGainsDb }?.name ?? "Custom"
    }

    private func gainBinding(_ band: Int) -> Binding<Float> {
        Binding(get: { eq.eqGainsDb[band] },
                set: { value in settings.update { $0.eqGainsDb[band] = value } })
    }

    private func dbText(_ db: Float) -> String {
        String(format: "%+.1f dB", db)
    }
}
