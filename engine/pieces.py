import random
import copy
from externalFunctions import *

def check_for_check(piece, board, new_position):
    playerKing = None
    current_position = piece.position

    updated_board = copy.deepcopy(board)
    updated_board[piece.position[0]][piece.position[1]] = Piece()
    updated_board[new_position[0]][new_position[1]] = piece
    piece.position = new_position

    for row in updated_board:
        for square in row:
            if square.pieceID == 6 and square.color == piece.color:
                playerKing = square
                break
    
    if playerKing == None:
        return False
    
    for row in updated_board:
        for square in row:
            if square.pieceID != 0 and square.color != piece.color:
                if square.move(updated_board, playerKing.position, ghost_move=True, playerAction=False):
                    piece.position = current_position
                    return True
   
    piece.position = current_position
    return False

class Piece:
    def __init__(self):
        self.moves = 0
        self.color = "white"
        self.position = [0, 0]    
        self.pieceID = 0
        self.uniqueID = random.randrange(0, 1000000000000)
    
    def init(self, color, position):
        self.color = color
        self.position = position        

    def move(self, new_position):
        self.position = new_position
        self.moves += 1

class Pawn(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 1

    def move(self, board, new_position, ghost_move=False, promote=Piece, playerAction=True):        
        if pieces_between(self.position, new_position, board)[0]:
            return False

        possible = False
        pasant = False

        # Inital pawn 2 up move

        if self.moves == 0:
            if self.color == "white":
                if moveDegree(self.position, new_position) == (-2, 0) and board[new_position[0]][new_position[1]].pieceID == 0:
                    possible = True
            else:                
                if moveDegree(self.position, new_position) == (2, 0) and board[new_position[0]][new_position[1]].pieceID == 0:
                    possible = True

        # Standard pawn 1 up move

        if self.color == "white":
            if moveDegree(self.position, new_position) == (-1, 0) and board[new_position[0]][new_position[1]].pieceID == 0:
                possible = True
        else:   
            if moveDegree(self.position, new_position) == (1, 0) and board[new_position[0]][new_position[1]].pieceID == 0:
                possible = True
            
        # Pawn capture

        if self.color == "white":
            if moveDegree(self.position, new_position) == (-1, 1) and board[new_position[0]][new_position[1]].pieceID != 0:
                possible = True
        else:                
            if moveDegree(self.position, new_position) == (1, 1) and board[new_position[0]][new_position[1]].pieceID != 0:
                possible = True

        # En passant

        if self.color == "white":
            if moveDegree(self.position, new_position) == (-1, -1) and board[new_position[0]][new_position[1]].pieceID == 0:
                if board[new_position[0] + 1][new_position[1]].pieceID == 1 and board[new_position[0] + 1][new_position[1]].pieceID == 1 and board[new_position[0] + 1][new_position[1]].moves == 1:
                    possible = True
                    pasant = True

        else:
            if moveDegree(self.position, new_position) == (1, 1) and board[new_position[0]][new_position[1]].pieceID == 0:
                if board[new_position[0] - 1][new_position[1]].pieceID == 1 and board[new_position[0] - 1][new_position[1]].pieceID == 1 and board[new_position[0] - 1][new_position[1]].moves == 1:
                    possible = True
                    pasant = True
           
        # check for check
        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:            
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            
                # promote 
                if self.color == "white":
                    if new_position[0] == 0:
                        if promote != Piece:
                            board[new_position[0]][new_position[1]] = promote()
                            board[new_position[0]][new_position[1]].init("white", new_position)
                            board[new_position[0]][new_position[1]].moves = self.moves
                            board[new_position[0]][new_position[1]].uniqueID = self.uniqueID
                else:
                    if new_position[0] == 7:
                        if promote != Piece:
                            board[new_position[0]][new_position[1]] = promote()
                            board[new_position[0]][new_position[1]].init("black", new_position)
                            board[new_position[0]][new_position[1]].moves = self.moves
                            board[new_position[0]][new_position[1]].uniqueID = self.uniqueID
                
                # pasant 
                if pasant:
                    if self.color == "white":
                        board[new_position[0] + 1][new_position[1]] = Piece()
                    else:
                        board[new_position[0] - 1][new_position[1]] = Piece()

            return True
        else:
            return False
        
class Knight(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 2

    def move(self, board, new_position, ghost_move=False, playerAction=True):
        possible = False

        thisMoveDegree = moveDegree(self.position, new_position)
        thisMoveDegree = (abs(thisMoveDegree[0]), abs(thisMoveDegree[1]))

        if thisMoveDegree == (2, 1):
            possible = True

        if thisMoveDegree == (1, 2):
            possible = True

        # capture logic

        piece = board[new_position[0]][new_position[1]]

        if piece.pieceID != 0:
            if piece.color == self.color:
                return False

        # check for check
        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            return True
        else:
            return False

class Bishop(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 3

    def move(self, board, new_position, ghost_move=False, playerAction=True):
        possible = False

        if abs(self.position[0] - new_position[0]) == abs(self.position[1] - new_position[1]):
            possible = True

        # capture logic

        piece = board[new_position[0]][new_position[1]]

        if piece.pieceID != 0:
            if piece.color == self.color:
                return False

        if pieces_between(self.position, new_position, board)[0]:
            return False

        # check for check
        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            return True
        else:
            return False 

class Rook(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 4

    def move(self, board, new_position, ghost_move=False, playerAction=True):
        possible = False

        if self.position[0] == new_position[0] or self.position[1] == new_position[1]:
            possible = True

        # capture logic

        piece = board[new_position[0]][new_position[1]]

        if piece.pieceID != 0:
            if piece.color == self.color:
                return False

        if pieces_between(self.position, new_position, board)[0]:
            return False

        # check for check
        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            return True
        else:
            return False 

class Queen(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 5

    def move(self, board, new_position, ghost_move=False, playerAction=True):
        possible = False

        if abs(self.position[0] - new_position[0]) == abs(self.position[1] - new_position[1]):
            possible = True

        if self.position[0] == new_position[0] or self.position[1] == new_position[1]:
            possible = True

        # capture logic

        piece = board[new_position[0]][new_position[1]]

        if piece.pieceID != 0:
            if piece.color == self.color:
                return False

        if pieces_between(self.position, new_position, board)[0]:
            for piece in pieces_between(self.position, new_position, board)[1]:
                return False

        # check for check
        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:            
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            return True
        else:
            return False 

class King(Piece):
    def __init__(self):
        super().__init__()
        self.pieceID = 6

    def move(self, board, new_position, ghost_move=False, playerAction=True):
        possible = False

        thisMoveDegree = moveDegree(self.position, new_position)
        thisMoveDegree = (abs(thisMoveDegree[0]), abs(thisMoveDegree[1]))

        if thisMoveDegree == (0, 1):
            possible = True

        if thisMoveDegree == (1, 0):
            possible = True

        if thisMoveDegree == (1, 1):
            possible = True

        piece = board[new_position[0]][new_position[1]]

        if piece.pieceID != 0:
            if piece.color == self.color:
                return False

        if playerAction:
            if check_for_check(self, board, new_position):
                return False

        if possible:
            if not ghost_move:
                self.position = new_position
                self.moves += 1
            return True
        else:
            return False 
        
    def checkmate(self, board):
        enemyKing = None
        
        imaginary_boards = 0
        invalid_boards = 0

        updated_board = copy.deepcopy(board)

        for row in updated_board:
            found = False
            
            for square in row:
                if square.pieceID == 6 and square.color != self.color:
                    enemyKing = square
                    found = True
                    break
            
            if found:
                break
        
        for i in range(8):
            for j in range(8):
                piece = updated_board[i][j]

                if piece.pieceID != 0:
                    if piece.color != self.color:
                        for k in range(8):
                            for l in range(8):                        
                                if piece.move(board, [k, l], ghost_move=True, playerAction=False) and piece.position != [k, l]:                                    
                                    check = False
                                    
                                    imaginary_boards += 1
                                    
                                    updated_board[piece.position[0]][piece.position[1]] = Piece()
                                    updated_board[k][l] = piece
                                    piece.position = [k, l]

                                    # at this point we are looking at every possible move for every piece on the enemy team and creating an imaginary board with those moves
                                    # now we need to check if the king is in check on that imaginary board for each possible move

                                    if piece.pieceID == 6:
                                        found = False
                                        
                                        for row in updated_board:
                                            for square in row:
                                                if square.pieceID == 6 and square.color != self.color:
                                                    enemyKing = square
                                                    found = True
                                                    break
                                                
                                            if found:
                                                break
                                                    
                                    # using imaginary board to check if king is in checkmate

                                    for row in updated_board:
                                        for square in row:
                                            if square.pieceID != 0 and square.color != piece.color:                                                
                                                if square.move(updated_board, enemyKing.position, ghost_move=True, playerAction=False):
                                                    check = True
                                                    break
                                        if check:
                                            break
                                                                        
                                    if check:
                                        invalid_boards += 1

                                    # reset imaginary board
                                    
                                    updated_board[i][j] = piece
                                    updated_board[k][l] = Piece()
                                    piece.position = [i, j]

        if imaginary_boards == invalid_boards:
            return True
        else:
            return False
