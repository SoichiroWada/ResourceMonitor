export const gb = (b: number) => (b / 1024 ** 3).toFixed(1) + " GB";

/** 使用率に応じた色の段階（CSS クラス名） */
export const level = (p: number) => (p >= 90 ? "bad" : p >= 75 ? "warn" : "");
