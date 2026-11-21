/**
 * Глобальная конфигурация веб-клиента.
 * URL сервера bridge можно поменять на экране подключения
 * или через localStorage.
 */
window.DSConfig = {
  DEFAULT_BASE_URL: "http://localhost:11434",
  STORAGE_KEY: "dsbridge.baseUrl",

  getBaseUrl() {
    return localStorage.getItem(this.STORAGE_KEY) || this.DEFAULT_BASE_URL;
  },

  setBaseUrl(url) {
    localStorage.setItem(this.STORAGE_KEY, url.replace(/\/+$/, ""));
  },

  // ← НОВОЕ: полная очистка сохранённого URL (logout)
  clearBaseUrl() {
    localStorage.removeItem(this.STORAGE_KEY);
  },

  // ← НОВОЕ: есть ли сохранённый URL (для решения об auto-connect)
  hasSavedUrl() {
    return localStorage.getItem(this.STORAGE_KEY) !== null;
  },
};