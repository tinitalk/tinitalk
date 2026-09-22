package notify

// Only the OS-rendered fallback needs server-side translations.
type fallbackText struct{ Incoming, Caller, Open, Session, Account string }

var fallbackTranslations = map[string]fallbackText{
	"de":      {"📞 Eingehender Anruf", "📞 {0} ruft an", "Tippen, um den eingehenden Anruf zu öffnen", "Auf einem anderen Gerät angemeldet", "Öffnen Sie TiniTalk, um Ihr Konto zu prüfen"},
	"en":      {"📞 Incoming call", "📞 {0} is calling", "Tap to open the incoming call", "Signed in on another device", "Open TiniTalk to check your account"},
	"es":      {"📞 Llamada entrante", "📞 {0} está llamando", "Pulsa para abrir la llamada entrante", "Sesión iniciada en otro dispositivo", "Abre TiniTalk para comprobar tu cuenta"},
	"fr":      {"📞 Appel entrant", "📞 {0} vous appelle", "Touchez pour ouvrir l’appel entrant", "Connexion sur un autre appareil", "Ouvrez TiniTalk pour vérifier votre compte"},
	"it":      {"📞 Chiamata in arrivo", "📞 {0} ti sta chiamando", "Tocca per aprire la chiamata in arrivo", "Accesso effettuato su un altro dispositivo", "Apri TiniTalk per controllare il tuo account"},
	"ja":      {"📞 着信", "📞 {0}から着信", "タップして着信を開く", "別の端末でログインしました", "TiniTalkを開いてアカウントを確認してください"},
	"ko":      {"📞 수신 전화", "📞 {0}님이 전화 중", "눌러서 수신 전화 열기", "다른 기기에서 로그인했습니다", "TiniTalk을 열어 계정을 확인하세요"},
	"pl":      {"📞 Połączenie przychodzące", "📞 {0} dzwoni", "Naciśnij, aby otworzyć połączenie przychodzące", "Zalogowano na innym urządzeniu", "Otwórz TiniTalk, aby sprawdzić konto"},
	"pt":      {"📞 Chamada recebida", "📞 {0} está ligando", "Toque para abrir a chamada recebida", "Login realizado em outro dispositivo", "Abra o TiniTalk para verificar sua conta"},
	"ru":      {"📞 Входящий звонок", "📞 {0} звонит", "Нажмите, чтобы открыть входящий звонок", "Выполнен вход на другом устройстве", "Откройте TiniTalk, чтобы проверить аккаунт"},
	"tr":      {"📞 Gelen arama", "📞 {0} arıyor", "Gelen aramayı açmak için dokunun", "Başka bir cihazda giriş yapıldı", "Hesabınızı kontrol etmek için TiniTalk’ı açın"},
	"zh-Hans": {"📞 来电", "📞 {0} 来电", "点击打开来电", "已在另一台设备上登录", "打开 TiniTalk 以检查账号"},
}

func fallbackMessages(language string) (string, fallbackText) {
	if messages, ok := fallbackTranslations[language]; ok {
		return language, messages
	}
	return "en", fallbackTranslations["en"]
}
