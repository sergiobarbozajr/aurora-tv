AuroraTV - Android TV (TCL A400M) - versao 0.7

=== GERAR O APK PELO GITHUB (sem Android Studio) ===
1. No GitHub, crie um repositorio novo (pode ser privado). Nome: aurora-tv.
2. Na pagina do repo, "Add file" > "Upload files". Arraste TODO o conteudo
   desta pasta (as subpastas app, gradle, .github etc.) e confirme o commit
   na branch main.
3. O build comeca sozinho. Abra a aba "Actions" do repo e espere terminar
   (fica verde). Se der erro (vermelho), abra e me mande a mensagem.
4. Clique no build concluido, role ate "Artifacts" e baixe "AuroraTV-apk".
   Dentro vem o app-debug.apk.

=== INSTALAR NA TCL A400M PELA WIFI ===
Opcao A - "Send files to TV":
  - Instale o app "Send files to TV" na TV e no celular.
  - Ponha o app-debug.apk no celular, abra o app, Send, escolha a TV.
  - Na TV, abra o arquivo recebido e confirme.
Opcao B - "Downloader" (by AFTVnews):
  - Suba o apk para um link (Google Drive link direto) e digite no Downloader.
Antes: Configuracoes > Apps > Seguranca e restricoes > Fontes desconhecidas,
autorize o app usado (Send files to TV / Downloader).

=== O QUE JA FUNCIONA ===
Abas por categoria; relogio, data e clima; TV AO VIVO por lista IPTV legal;
player ExoPlayer com HDR10 nativo e troca de canal; Configuracoes com rede,
velocidade, guia de imagem da TCL, teste de tela e sobre.
