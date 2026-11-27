import Foundation

/// Menu language, chosen in the Language menu and stored in UserDefaults. Default: English.
public enum AppLanguage: String, CaseIterable, Sendable {
    case en
    case vi

    public static let defaultsKey = "appLanguage"

    public static var current: AppLanguage {
        get { AppLanguage(rawValue: UserDefaults.standard.string(forKey: defaultsKey) ?? "") ?? .en }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: defaultsKey) }
    }

    public var displayName: String {
        switch self {
        case .en: return "English"
        case .vi: return "Tiếng Việt"
        }
    }
}

/// Text kept in both languages so a language switch re-renders without new daemon events.
public struct LocalizedText: Equatable, Sendable {
    public let en: String
    public let vi: String

    public init(_ en: String, _ vi: String) {
        self.en = en
        self.vi = vi
    }

    /// Same text in both languages (e.g. a daemon error message).
    public init(verbatim: String) {
        self.init(verbatim, verbatim)
    }

    public func text(in language: AppLanguage = .current) -> String {
        language == .vi ? vi : en
    }
}

/// Picks the string for the current menu language.
public func L(_ en: String, _ vi: String) -> String {
    AppLanguage.current == .vi ? vi : en
}
