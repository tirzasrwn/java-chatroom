build: kill server client
	tmux-windowizer server java Server
	tmux-windowizer client1 java Client
	tmux-windowizer client2 java Client

server:
	javac Server.java

client:
	javac Client.java

kill:
	- tmux kill-window -t server
	- tmux kill-window -t client1
	- tmux kill-window -t client2
