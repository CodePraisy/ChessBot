import time

def pieces_between(start, end, board):
    is_pieces_between = False
    pieces = []
    start_x, start_y = start
    end_x, end_y = end

    if start_x == end_x:  # Vertical movement
        step = 1 if start_y < end_y else -1
        for y in range(start_y + step, end_y, step):
            if board[start_x][y].pieceID != 0:
                pieces.append(board[start_x][y])
                break
    
    elif start_y == end_y:  # Horizontal movement
        step = 1 if start_x < end_x else -1
        for x in range(start_x + step, end_x, step):
            if board[x][start_y].pieceID != 0:
                pieces.append(board[x][start_y])
                break
    
    elif abs(start_x - end_x) == abs(start_y - end_y):  # Diagonal movement
        step_x = 1 if start_x < end_x else -1
        step_y = 1 if start_y < end_y else -1
        x, y = start_x + step_x, start_y + step_y
        while x != end_x and y != end_y:
            if board[x][y].pieceID != 0:
                pieces.append(board[x][y])
                break
            x += step_x
            y += step_y

    if len(pieces) > 0:
        is_pieces_between = True

    return is_pieces_between, pieces

def print_board(board):
    piece_symbols = {
        "Pawn": {"white": "♟", "black": "♙"},
        "Rook": {"white": "♜", "black": "♖"},
        "Knight": {"white": "♞", "black": "♘"},
        "Bishop": {"white": "♝", "black": "♗"},
        "Queen": {"white": "♛", "black": "♕"},
        "King": {"white": "♚", "black": "♔"},
        "Empty": {"white": "·", "black": "·"}
    }
    print("\n   ------------------------")
    
    for i in range(8):
        row_str = f"{i + 1} |"
        for j in range(8):
            piece = board[i][j]
            piece_type = piece.__class__.__name__
            original_color = piece.color if hasattr(piece, "color") else "white"
            symbol = piece_symbols.get(piece_type, piece_symbols["Empty"])[original_color]
            row_str += f" {symbol} "
        print(row_str)
    
    print("   ------------------------")
    print("    a  b  c  d  e  f  g  h")

def time_function(func, *args, **kwargs):
    start_time = time.perf_counter()
    result = func(*args, **kwargs)
    end_time = time.time()
    print(f"Execution time: {end_time - start_time} seconds")
    return result


def moveDegree(current_posistion, new_position):
    return (new_position[0] - current_posistion[0], new_position[1] - current_posistion[1])


