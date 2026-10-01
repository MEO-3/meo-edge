pub type BleResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;
