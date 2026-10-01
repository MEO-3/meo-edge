#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub enum LogLevel {
    Verbose = 0,
    Info = 1,
    Debug = 2,
    Warning = 3,
    Error = 4,
}

impl LogLevel {
    fn label(self) -> &'static str {
        match self {
            LogLevel::Verbose => "VERBOSE",
            LogLevel::Info => "INFO",
            LogLevel::Debug => "DEBUG",
            LogLevel::Warning => "WARNING",
            LogLevel::Error => "ERROR",
        }
    }
}

#[derive(Clone)]
pub struct Log {
    enabled: bool,
    level: LogLevel,
}

impl Log {
    pub fn new(level: LogLevel) -> Self {
        Self {
            enabled: true,
            level,
        }
    }

    pub fn disabled() -> Self {
        Self {
            enabled: false,
            level: LogLevel::Error,
        }
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    pub fn set_level(&mut self, level: LogLevel) {
        self.level = level;
    }

    pub fn log(&self, level: LogLevel, tag: &str, message: &str) {
        if !self.should_log(level) {
            return;
        }

        let line = format!("[{}][{}] {}", level.label(), tag, message);
        match level {
            LogLevel::Warning | LogLevel::Error => eprintln!("{line}"),
            _ => println!("{line}"),
        }
    }

    pub fn verbose(&self, tag: &str, message: &str) {
        self.log(LogLevel::Verbose, tag, message);
    }

    pub fn info(&self, tag: &str, message: &str) {
        self.log(LogLevel::Info, tag, message);
    }

    pub fn debug(&self, tag: &str, message: &str) {
        self.log(LogLevel::Debug, tag, message);
    }

    pub fn warning(&self, tag: &str, message: &str) {
        self.log(LogLevel::Warning, tag, message);
    }

    pub fn error(&self, tag: &str, message: &str) {
        self.log(LogLevel::Error, tag, message);
    }

    fn should_log(&self, level: LogLevel) -> bool {
        self.enabled && level >= self.level
    }
}

impl Default for Log {
    fn default() -> Self {
        Self::new(LogLevel::Debug)
    }
}

pub type ILog = Log;
