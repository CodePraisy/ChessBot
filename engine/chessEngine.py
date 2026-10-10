import time
import random
import numpy

from pieces import *

class Chess:
    def __init__(self): 
        self.board = numpy.array([[Piece() for i in range(8)] for j in range(8)])
        self.turn = 1  #1 for white, -1 for black
        self.moves = 0
        self.gameID = random.randrange(0, 1000000000000)
        self.status = 0 # 0 for ongoing, 1 for white win, -1 for black win

        # initilize board

        for i in range(8):
            for j in range(8):
                temp = Piece()
                temp.init("white", [i, j])
                
                self.board[i][j] = temp

        for i in range(8):
            pawn = Pawn()
            pawn.init("black", [1, i])
            
            self.board[1][i] = pawn       

        for i in range(8):
            pawn = Pawn()
            pawn.init("white", [6, i])
            
            self.board[6][i] = pawn

        piece_order = [Rook, Knight, Bishop, Queen, King, Bishop, Knight, Rook]
        
        for i, piece in enumerate(piece_order):
            blackPiece = piece()
            blackPiece.init("black", [0, i])

            whitePiece = piece()
            whitePiece.init("white", [7, i])
            
            self.board[0][i] = blackPiece  # Black pieces
            self.board[7][i] = whitePiece  # White pieces

    def move(self, player, current_posistion, new_position, ghost_move=False, promote=Piece):
        start_time = time.perf_counter()
        
        if not self.pieces.prerequisitesChecks(self, player, current_posistion, new_position):
            return False

        if self.status != 0:
            return False

        piece = self.board[current_posistion[0]][current_posistion[1]]        
        
        if piece.pieceID == 0:
            return False

        valid = False

        if type(piece) == Pawn:
            valid = piece.move(self.board, new_position, ghost_move=ghost_move, promote=promote)
        else:
            valid = piece.move(self.board, new_position, ghost_move=ghost_move)

        if valid:
            self.turn *= -1
            self.moves += 1

            self.board[new_position[0]][new_position[1]] = piece
            self.board[current_posistion[0]][current_posistion[1]] = Piece()

            checkmate = self.checkmate(player, piece)
            
            if checkmate:
                self.status = player

        print(f"Execution time: {time.perf_counter() - start_time} seconds")

        return valid
    
    def checkmate(self, player, checking_piece):
        check = False
        checkmate = False

        for row in range(8):
            for col in range(8):
                piece = self.board[row][col]
                
                if piece.color != player and piece.pieceID == 6:
                    if checking_piece.move(self.board, [row, col], ghost_move=True):
                        check = True
                        break

        if not check:
            return False

        for row in range(8):
            for col in range(8):
                square = self.board[row][col]
                
                if square.color == "white" and player == 1:
                    if square.pieceID == 6:
                        checkmate = square.checkmate(self.board)
                        break
                
                elif square.color == "black" and player == -1:
                    if square.pieceID == 6:
                        checkmate = square.checkmate(self.board)
                        break
        
        return checkmate
    
    def simple_board(self):
        simple_board = numpy.zeros((8, 8), dtype=int)
        
        for i in range(8):
            for j in range(8):
                piece = self.board[i][j]
                
                if piece.pieceID == 0:
                    simple_board[i][j] = 0
                else:
                    if piece.color == "white":
                        simple_board[i][j] = piece.pieceID
                    else:
                     simple_board[i][j] = piece.pieceID * -1

        return simple_board

    def print_board(self):
        piece_symbols = {
            "Pawn": {"white": "♟", "black": "♙"},
            "Rook": {"white": "♜", "black": "♖"},
            "Knight": {"white": "♞", "black": "♘"},
            "Bishop": {"white": "♝", "black": "♗"},
            "Queen": {"white": "♛", "black": "♕"},
            "King": {"white": "♚", "black": "♔"},
            "Empty": {"white": "·", "black": "·"}
        }

        print("\n-------------------------------------------")

        print("Chess Board || GameID: " + str(self.gameID))

        print("\nMoves: " + str(self.moves))
        print("Turn: " + "White" if self.turn == 1 else "Turn: Black")
        print("Status: " + "Ongoing" if self.status == 0 else "Status: White Won!" if self.status == 1 else "Status: Black Won!")

        print("\n   ------------------------")
        
        for i in range(8):
            row_str = f"{i + 1} |"
            for j in range(8):
                piece = self.board[i][j]
                piece_type = piece.__class__.__name__
                original_color = piece.color if hasattr(piece, "color") else "white"
                symbol = piece_symbols.get(piece_type, piece_symbols["Empty"])[original_color]
                row_str += f" {symbol} "
            print(row_str)
        
        print("   ------------------------")
        print("    a  b  c  d  e  f  g  h")
        
        print("-------------------------------------------")

    class pieces:
        @staticmethod
        
        def prerequisitesChecks(self, player, current_posistion, new_position):                        
            playerColor = "white" if player == 1 else "black"
            
            if player != self.turn:
                return False
            
            if current_posistion[0] < 0 or current_posistion[0] > 7 or current_posistion[1] < 0 or current_posistion[1] > 7:
                return False

            if new_position[0] < 0 or new_position[0] > 7 or new_position[1] < 0 or new_position[1] > 7:
               return False
            
            if current_posistion == new_position:
                return False
            
            if self.board[current_posistion[0]][current_posistion[1]].color != playerColor:
                return False
            
            return True
    
